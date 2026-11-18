//! Direct read-only legacy TCP ADB inspection.
//!
//! This deliberately exposes no device-changing operations. The caller supplies
//! one IP address; the application connects only to that host on TCP 5555.

use std::{
    net::{IpAddr, SocketAddr},
    time::Duration,
};

use libadb::{Connection, ConnectionConfig, Feature, HostBanner, TokioTcp};
use tokio::{net::TcpStream, time::timeout};

use crate::{cng_auth::WindowsCngAdbKey, device_profile::DeviceProfile};

const ADB_PORT: u16 = 5555;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
const AUTH_TIMEOUT: Duration = Duration::from_secs(60);
const INVENTORY_TIMEOUT: Duration = Duration::from_secs(15);
const GETPROP_OUTPUT_LIMIT: usize = 64 * 1024;

/// Connect to exactly one owner-specified Fire TV IP and read its properties.
/// This performs no package, setting, launcher or other device modifications.
pub async fn inspect_device(ip: IpAddr) -> Result<DeviceProfile, String> {
    if !is_local_target(ip) {
        return Err("refusing to connect to a non-local IP address".into());
    }
    let address = SocketAddr::new(ip, ADB_PORT);
    let stream = timeout(CONNECT_TIMEOUT, TcpStream::connect(address))
        .await
        .map_err(|_| "timed out connecting to the Fire TV ADB port".to_owned())?
        .map_err(|error| format!("could not connect to {address}: {error}"))?;
    stream
        .set_nodelay(true)
        .map_err(|error| format!("could not configure the ADB socket: {error}"))?;

    let transport = TokioTcp::new(stream);
    let key = WindowsCngAdbKey::open_or_create()?;
    let mut connection = timeout(
        AUTH_TIMEOUT,
        Connection::connect(
            transport,
            key,
            HostBanner::Features(&[Feature::ShellV2]),
            ConnectionConfig::new()
                .with_max_payload(64 * 1024)
                .with_max_rx_per_channel(64 * 1024)
                .with_max_channels(1),
        ),
    )
    .await
    .map_err(|_| "ADB authentication timed out; check the TV's RSA authorisation prompt".to_owned())?
    .map_err(|error| format!("ADB handshake/authentication failed: {error}"))?;

    let mut output = vec![0u8; GETPROP_OUTPUT_LIMIT];
    let written = timeout(
        INVENTORY_TIMEOUT,
        libadb::shell::v1::exec(&mut connection, "getprop", &mut output),
    )
    .await
    .map_err(|_| "timed out reading device properties".to_owned())?
    .map_err(|error| format!("could not read device properties: {error}"))?;

    let text = String::from_utf8_lossy(&output[..written]);
    DeviceProfile::parse_getprop_output(&text)
}

fn is_local_target(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(address) => {
            address.is_private() || address.is_loopback() || address.is_link_local()
        }
        IpAddr::V6(address) => {
            address.is_unique_local() || address.is_loopback() || address.is_unicast_link_local()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_private_or_link_local_addresses_are_allowed() {
        assert!(is_local_target("192.168.1.25".parse().unwrap()));
        assert!(is_local_target("10.0.0.5".parse().unwrap()));
        assert!(is_local_target("169.254.1.2".parse().unwrap()));
        assert!(is_local_target("fd00::1234".parse().unwrap()));
        assert!(!is_local_target("8.8.8.8".parse().unwrap()));
        assert!(!is_local_target("2001:4860:4860::8888".parse().unwrap()));
    }
}
