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
            ConnectionConfig::new(),
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
