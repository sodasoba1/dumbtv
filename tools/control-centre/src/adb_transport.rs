//! Direct read-only legacy TCP ADB inspection.
//!
//! This deliberately exposes no device-changing operations. The caller supplies
//! one IP address; the application connects only to that host on TCP 5555.

use std::{
    net::{IpAddr, SocketAddr},
    time::Duration,
};

use libadb::{Connection, ConnectionConfig, HostBanner, TokioTcp};
use tokio::{net::TcpStream, time::timeout};

use crate::{
    cng_auth::WindowsCngAdbKey,
    device_profile::DeviceProfile,
    package_inventory::PackageInventory,
};

const ADB_PORT: u16 = 5555;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
const AUTH_TIMEOUT: Duration = Duration::from_secs(60);
const INVENTORY_TIMEOUT: Duration = Duration::from_secs(20);
const COMMAND_OUTPUT_LIMIT: usize = 256 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceInventory {
    pub profile: DeviceProfile,
    pub packages: Option<PackageInventory>,
}

#[derive(Clone, Copy)]
enum ReadOnlyCommand {
    GetProp,
    AllPackages,
    SystemPackages,
    DisabledPackages,
}

impl ReadOnlyCommand {
    fn as_str(self) -> &'static str {
        match self {
            Self::GetProp => "getprop",
            Self::AllPackages => "pm list packages",
            Self::SystemPackages => "pm list packages -s",
            Self::DisabledPackages => "pm list packages -d",
        }
    }
}

/// Connect to exactly one owner-specified Fire TV IP and read its properties.
/// This performs no package, setting, launcher or other device modifications.
pub async fn inspect_device(ip: IpAddr) -> Result<DeviceInventory, String> {
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
            HostBanner::Features(&[]),
            ConnectionConfig::new()
                .with_max_payload(64 * 1024)
                .with_max_rx_per_channel(COMMAND_OUTPUT_LIMIT)
                .with_max_channels(1),
        ),
    )
    .await
    .map_err(|_| "ADB authentication timed out; check the TV's RSA authorisation prompt".to_owned())?
    .map_err(|error| format!("ADB handshake/authentication failed: {error}"))?;

    let properties = run_read_only_command(&mut connection, ReadOnlyCommand::GetProp).await?;
    let profile = DeviceProfile::parse_getprop_output(&properties)?;

    // Do not collect installed-app inventory from an unrecognised or
    // unvalidated Android device. Identity is the first compatibility gate.
    if profile.cleanup_compatibility() != crate::device_profile::Compatibility::Supported {
        return Ok(DeviceInventory { profile, packages: None });
    }

    let all_packages = run_read_only_command(&mut connection, ReadOnlyCommand::AllPackages).await?;
    let system_packages = run_read_only_command(&mut connection, ReadOnlyCommand::SystemPackages).await?;
    let disabled_packages =
        run_read_only_command(&mut connection, ReadOnlyCommand::DisabledPackages).await?;
    let packages = PackageInventory::from_pm_outputs(
        &all_packages,
        &system_packages,
        &disabled_packages,
    )?;

    Ok(DeviceInventory { profile, packages: Some(packages) })
}

async fn run_read_only_command(
    connection: &mut Connection<TokioTcp>,
    command: ReadOnlyCommand,
) -> Result<String, String> {
    let mut output = vec![0u8; COMMAND_OUTPUT_LIMIT];
    let written = timeout(
        INVENTORY_TIMEOUT,
        libadb::shell::v1::exec(connection, command.as_str(), &mut output),
    )
    .await
    .map_err(|_| format!("timed out running read-only inventory command: {}", command.as_str()))?
    .map_err(|error| format!("read-only inventory command failed ({}): {error}", command.as_str()))?;

    Ok(String::from_utf8_lossy(&output[..written]).into_owned())
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
