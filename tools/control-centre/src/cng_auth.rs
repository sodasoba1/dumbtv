//! Windows CNG-backed ADB host key.
//!
//! The private key is generated and retained by the current user's Windows
//! Software Key Storage Provider. It is non-exportable; only the public
//! modulus/exponent leave the provider. RSA/SHA-1 is used solely because
//! legacy Android ADB AUTH mandates PKCS#1 v1.5 with SHA-1.

use sha1::{Digest, Sha1};
use std::ptr;

use windows_sys::Win32::Security::Cryptography::{
    NCryptCreatePersistedKey, NCryptDeleteKey, NCryptExportKey, NCryptFinalizeKey,
    NCryptFreeObject, NCryptOpenKey, NCryptOpenStorageProvider, NCryptSetProperty, NCryptSignHash, NCryptVerifySignature,
    BCRYPT_PKCS1_PADDING_INFO,
};

use crate::adb_auth::AdbPublicKey;

const STATUS_SUCCESS: i32 = 0;
const NTE_BAD_KEYSET: i32 = 0x8009_0016u32 as i32;
const NTE_NOT_FOUND: i32 = 0x8009_0011u32 as i32;
const NCRYPT_PAD_PKCS1_FLAG: u32 = 0x0000_0002;
const RSA_BITS: u32 = 2048;

const PROVIDER: [u16; 40] = wide("Microsoft Software Key Storage Provider");
const RSA_ALGORITHM: [u16; 4] = wide("RSA");
const KEY_NAME: [u16; 33] = wide("DumbTV-ControlCentre-ADB-HostKey");
const LENGTH_PROPERTY: [u16; 7] = wide("Length");
const RSA_PUBLIC_BLOB: [u16; 14] = wide("RSAPUBLICBLOB");
const SHA1_ALGORITHM: [u16; 5] = wide("SHA1");
const PUBLIC_KEY_NAME: &str = "dumbtv-control-centre@windows";

const fn wide<const N: usize>(value: &str) -> [u16; N] {
    let bytes = value.as_bytes();
    let mut output = [0u16; N];
    let mut index = 0;
    while index < bytes.len() {
        output[index] = bytes[index] as u16;
        index += 1;
    }
    output
}

pub struct WindowsCngAdbKey {
    provider: isize,
    key: isize,
    public_key: AdbPublicKey,
}

impl WindowsCngAdbKey {
    /// Open the dedicated current-user key, creating it once if absent.
    /// Existing keys are never overwritten.
    pub fn open_or_create() -> Result<Self, String> {
        let mut provider = 0isize;
        let status = unsafe {
            NCryptOpenStorageProvider(&mut provider, PROVIDER.as_ptr(), 0)
        };
        check_status(status, "open Windows CNG key provider")?;

        let mut key = 0isize;
        let open_status = unsafe {
            NCryptOpenKey(provider, &mut key, KEY_NAME.as_ptr(), 0, 0)
        };

        if open_status != STATUS_SUCCESS {
            if open_status != NTE_BAD_KEYSET && open_status != NTE_NOT_FOUND {
                unsafe { NCryptFreeObject(provider); }
                return Err(format!("open existing ADB host key failed (0x{:08x})", open_status as u32));
            }

            let create_status = unsafe {
                NCryptCreatePersistedKey(
                    provider,
                    &mut key,
                    RSA_ALGORITHM.as_ptr(),
                    KEY_NAME.as_ptr(),
                    0,
                    0,
                )
            };
            if create_status != STATUS_SUCCESS {
                unsafe { NCryptFreeObject(provider); }
                return Err(format!("create ADB host key failed (0x{:08x})", create_status as u32));
            }

            let key_length = RSA_BITS.to_le_bytes();
            let set_status = unsafe {
                NCryptSetProperty(
                    key,
                    LENGTH_PROPERTY.as_ptr(),
                    key_length.as_ptr(),
                    key_length.len() as u32,
                    0,
                )
            };
            if set_status != STATUS_SUCCESS {
                unsafe {
                    if NCryptDeleteKey(key, 0) != STATUS_SUCCESS {
                        NCryptFreeObject(key);
                    }
                    NCryptFreeObject(provider);
                }
                return Err(format!("set ADB host key length failed (0x{:08x})", set_status as u32));
            }

            let finalize_status = unsafe { NCryptFinalizeKey(key, 0) };
            if finalize_status != STATUS_SUCCESS {
                unsafe {
                    if NCryptDeleteKey(key, 0) != STATUS_SUCCESS {
                        NCryptFreeObject(key);
                    }
                    NCryptFreeObject(provider);
                }
                return Err(format!("finalize ADB host key failed (0x{:08x})", finalize_status as u32));
            }
        }

        let public_key = match export_public_key(key) {
            Ok(value) => value,
            Err(error) => {
                unsafe {
                    NCryptFreeObject(key);
                    NCryptFreeObject(provider);
                }
                return Err(error);
            }
        };

        Ok(Self { provider, key, public_key })
    }

    /// Generate an ephemeral key for tests. It is never persisted in the
    /// user's Windows key store.
    #[cfg(test)]
    fn generate_ephemeral() -> Result<Self, String> {
        let mut provider = 0isize;
        let status = unsafe {
            NCryptOpenStorageProvider(&mut provider, PROVIDER.as_ptr(), 0)
        };
        check_status(status, "open Windows CNG key provider")?;

        let mut key = 0isize;
        let create_status = unsafe {
            NCryptCreatePersistedKey(
                provider,
                &mut key,
                RSA_ALGORITHM.as_ptr(),
                ptr::null(),
                0,
                0,
            )
        };
        if create_status != STATUS_SUCCESS {
            unsafe { NCryptFreeObject(provider); }
            return Err(format!("create ephemeral ADB test key failed (0x{:08x})", create_status as u32));
        }

        let key_length = RSA_BITS.to_le_bytes();
        let set_status = unsafe {
            NCryptSetProperty(
                key,
                LENGTH_PROPERTY.as_ptr(),
                key_length.as_ptr(),
                key_length.len() as u32,
                0,
            )
        };
        if set_status != STATUS_SUCCESS {
            unsafe {
                NCryptFreeObject(key);
                NCryptFreeObject(provider);
            }
            return Err(format!("set ephemeral ADB key length failed (0x{:08x})", set_status as u32));
        }

        let finalize_status = unsafe { NCryptFinalizeKey(key, 0) };
        if finalize_status != STATUS_SUCCESS {
            unsafe {
                NCryptFreeObject(key);
                NCryptFreeObject(provider);
            }
            return Err(format!("finalize ephemeral ADB key failed (0x{:08x})", finalize_status as u32));
        }

        let public_key = match export_public_key(key) {
            Ok(value) => value,
            Err(error) => {
                unsafe {
                    NCryptFreeObject(key);
                    NCryptFreeObject(provider);
                }
                return Err(error);
            }
        };

        Ok(Self { provider, key, public_key })
    }

    pub fn public_key_wire(&self) -> &[u8] {
        self.public_key.as_wire_payload()
    }

    /// Sign the device's 20-byte ADB AUTH token using Windows CNG.
    pub fn sign_token(&self, token: &[u8]) -> Result<Vec<u8>, String> {
        if token.len() != 20 {
            return Err(format!("ADB AUTH token must be 20 bytes, received {}", token.len()));
        }

        let digest = Sha1::digest(token);
        let padding = BCRYPT_PKCS1_PADDING_INFO {
            pszAlgId: SHA1_ALGORITHM.as_ptr(),
        };
        let mut signature = vec![0u8; 256];
        let mut written = 0u32;
        let status = unsafe {
            NCryptSignHash(
                self.key,
                &padding as *const BCRYPT_PKCS1_PADDING_INFO as *const _,
                digest.as_ptr(),
                digest.len() as u32,
                signature.as_mut_ptr(),
                signature.len() as u32,
                &mut written,
                NCRYPT_PAD_PKCS1_FLAG,
            )
        };
        check_status(status, "sign ADB AUTH token")?;
        if written != 256 {
            return Err(format!("CNG returned an unexpected RSA signature length: {written}"));
        }
        signature.truncate(written as usize);
        Ok(signature)
    }
}

impl Drop for WindowsCngAdbKey {
    fn drop(&mut self) {
        unsafe {
            if self.key != 0 {
                NCryptFreeObject(self.key);
            }
            if self.provider != 0 {
                NCryptFreeObject(self.provider);
            }
        }
    }
}

fn export_public_key(key: isize) -> Result<AdbPublicKey, String> {
    // A 2048-bit RSA public blob is only 24 header bytes plus exponent and
    // modulus. A fixed upper bound avoids a probe call and is checked below.
    let mut blob = vec![0u8; 512];
    let mut written = 0u32;
    let status = unsafe {
        NCryptExportKey(
            key,
            0,
            RSA_PUBLIC_BLOB.as_ptr(),
            ptr::null(),
            blob.as_mut_ptr(),
            blob.len() as u32,
            &mut written,
            0,
        )
    };
    check_status(status, "export ADB public key")?;
    if written < 24 || written as usize > blob.len() {
        return Err("Windows CNG returned an invalid RSA public-key blob size".into());
    }
    blob.truncate(written as usize);

    let read_u32 = |offset: usize| -> Result<u32, String> {
        let bytes: [u8; 4] = blob
            .get(offset..offset + 4)
            .ok_or_else(|| "truncated Windows RSA public-key header".to_owned())?
            .try_into()
            .map_err(|_| "invalid Windows RSA public-key header".to_owned())?;
        Ok(u32::from_le_bytes(bytes))
    };

    let magic = read_u32(0)?;
    let bit_length = read_u32(4)?;
    let exponent_len = read_u32(8)? as usize;
    let modulus_len = read_u32(12)? as usize;
    let total = 24usize
        .checked_add(exponent_len)
        .and_then(|size| size.checked_add(modulus_len))
        .ok_or_else(|| "RSA public-key blob length overflow".to_owned())?;

    if magic != 0x3141_5352 || bit_length != RSA_BITS || modulus_len != 256 || total != blob.len() {
        return Err("Windows CNG returned an unsupported RSA public-key blob".into());
    }
    let exponent = &blob[24..24 + exponent_len];
    let modulus = &blob[24 + exponent_len..total];
    AdbPublicKey::from_rsa_components(modulus, exponent, PUBLIC_KEY_NAME)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cng_generates_key_exports_public_key_and_signs_adb_token() {
        let key = WindowsCngAdbKey::generate_ephemeral().unwrap();
        assert!(key.public_key_wire().ends_with(b" dumbtv-control-centre@windows\\0"));

        let token = [0x5au8; 20];
        let signature = key.sign_token(&token).unwrap();
        assert_eq!(signature.len(), 256);

        let digest = Sha1::digest(token);
        let padding = BCRYPT_PKCS1_PADDING_INFO {
            pszAlgId: SHA1_ALGORITHM.as_ptr(),
        };
        let status = unsafe {
            NCryptVerifySignature(
                key.key,
                &padding as *const BCRYPT_PKCS1_PADDING_INFO as *const _,
                digest.as_ptr(),
                digest.len() as u32,
                signature.as_ptr(),
                signature.len() as u32,
                NCRYPT_PAD_PKCS1_FLAG,
            )
        };
        assert_eq!(status, STATUS_SUCCESS);
    }
}

fn check_status(status: i32, operation: &str) -> Result<(), String> {
    if status == STATUS_SUCCESS {
        Ok(())
    } else {
        Err(format!("{operation} failed (Windows status 0x{:08x})", status as u32))
    }
}
