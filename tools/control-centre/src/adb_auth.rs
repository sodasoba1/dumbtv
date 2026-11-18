//! Android's legacy mincrypt public-key encoding used by ADB AUTH.
//!
//! This is deliberately separate from private-key generation/signing. The
//! Windows CNG adapter supplies the RSA modulus and exponent; this module
//! validates and encodes only the public material.

use base64::{engine::general_purpose::STANDARD, Engine as _};
use md5::Md5;
use num_bigint::BigUint;
use sha2::{Digest, Sha256};

const MODULUS_BYTES: usize = 256;
const MODULUS_WORDS: u32 = (MODULUS_BYTES / 4) as u32;
const STRUCT_BYTES: usize = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdbPublicKey {
    wire_payload: Vec<u8>,
    fingerprint_md5: [u8; 16],
    fingerprint_sha256: [u8; 32],
}

impl AdbPublicKey {
    /// Build the NUL-terminated ADB AUTH public-key payload from big-endian
    /// RSA modulus and exponent values exported by the Windows crypto provider.
    pub fn from_rsa_components(
        modulus_be: &[u8],
        exponent_be: &[u8],
        key_name: &str,
    ) -> Result<Self, String> {
        if modulus_be.len() != MODULUS_BYTES || modulus_be[0] == 0 {
            return Err("ADB requires a 2048-bit RSA modulus".into());
        }
        if modulus_be[MODULUS_BYTES - 1] & 1 == 0 {
            return Err("RSA modulus must be odd".into());
        }
        let exponent_be = trim_leading_zeroes(exponent_be);
        if exponent_be.is_empty() || exponent_be.len() > 4 {
            return Err("RSA public exponent must fit in 32 bits".into());
        }
        let mut exponent_word = [0u8; 4];
        exponent_word[4 - exponent_be.len()..].copy_from_slice(exponent_be);
        let exponent = u32::from_be_bytes(exponent_word);
        if exponent < 3 || exponent % 2 == 0 {
            return Err("RSA public exponent must be odd and at least 3".into());
        }
        if key_name.is_empty()
            || key_name.bytes().any(|byte| matches!(byte, 0 | b'\r' | b'\n'))
            || key_name.len() > 64
        {
            return Err("invalid ADB public-key name".into());
        }

        let modulus = BigUint::from_bytes_be(modulus_be);
        let mut modulus_le = modulus.to_bytes_le();
        modulus_le.resize(MODULUS_BYTES, 0);

        let n0 = u32::from_le_bytes(modulus_le[..4].try_into().expect("four bytes"));
        let n0_inverse = negative_inverse_mod_2_32(n0);

        let mut rr_le =
            ((BigUint::from(1u32) << (MODULUS_BYTES * 16)) % &modulus).to_bytes_le();
        rr_le.resize(MODULUS_BYTES, 0);

        let mut blob = Vec::with_capacity(STRUCT_BYTES);
        blob.extend_from_slice(&MODULUS_WORDS.to_le_bytes());
        blob.extend_from_slice(&n0_inverse.to_le_bytes());
        blob.extend_from_slice(&modulus_le);
        blob.extend_from_slice(&rr_le);
        blob.extend_from_slice(&exponent.to_le_bytes());

        debug_assert_eq!(blob.len(), STRUCT_BYTES);
        let fingerprint_md5: [u8; 16] = Md5::digest(&blob).into();
        let fingerprint_sha256: [u8; 32] = Sha256::digest(&blob).into();
        let mut wire_payload = STANDARD.encode(blob).into_bytes();
        wire_payload.push(b' ');
        wire_payload.extend_from_slice(key_name.as_bytes());
        wire_payload.push(0);

        Ok(Self { wire_payload, fingerprint_md5, fingerprint_sha256 })
    }

    pub fn as_wire_payload(&self) -> &[u8] {
        &self.wire_payload
    }

    /// MD5 fingerprint used by the Android 7-era ADB authorisation dialog.
    /// This is a display identifier only; it is not used for cryptographic
    /// authentication or integrity decisions.
    pub fn fingerprint_md5(&self) -> String {
        self.fingerprint_md5
            .iter()
            .map(|byte| format!("{byte:02X}"))
            .collect::<Vec<_>>()
            .join(":")
    }

    /// SHA-256 fingerprint of the raw mincrypt public-key structure.
    pub fn fingerprint_sha256(&self) -> String {
        self.fingerprint_sha256.iter().map(|byte| format!("{byte:02x}")).collect()
    }
}

fn trim_leading_zeroes(value: &[u8]) -> &[u8] {
    let first = value.iter().position(|byte| *byte != 0).unwrap_or(value.len());
    &value[first..]
}

fn negative_inverse_mod_2_32(odd: u32) -> u32 {
    // Newton iteration for the inverse modulo 2^32. The initial odd value
    // is already an inverse modulo 8; four iterations reach 32 bits.
    let mut inverse = odd;
    for _ in 0..4 {
        inverse = inverse.wrapping_mul(2u32.wrapping_sub(odd.wrapping_mul(inverse)));
    }
    inverse.wrapping_neg()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_2048_bit_rsa_public_key_in_adb_mincrypt_layout() {
        // A fixed 2048-bit odd modulus is sufficient to verify the public
        // wire-format arithmetic; private-key operations are tested by CNG.
        let modulus = [0xffu8; MODULUS_BYTES];
        let key = AdbPublicKey::from_rsa_components(&modulus, &[1, 0, 1], "dumbtv@test")
            .unwrap();
        let payload = key.as_wire_payload();
        assert!(payload.ends_with(b" dumbtv@test\0"));
        assert_eq!(key.fingerprint_md5().len(), 47);
        assert_eq!(key.fingerprint_sha256().len(), 64);

        let encoded = std::str::from_utf8(&payload[..payload.len() - 1]).unwrap();
        let blob_text = encoded.split(' ').next().unwrap();
        let blob = STANDARD.decode(blob_text).unwrap();
        assert_eq!(blob.len(), STRUCT_BYTES);
        assert_eq!(u32::from_le_bytes(blob[..4].try_into().unwrap()), MODULUS_WORDS);
        assert_eq!(u32::from_le_bytes(blob[STRUCT_BYTES - 4..].try_into().unwrap()), 65537);

        let n0 = u32::from_le_bytes(blob[8..12].try_into().unwrap());
        let n0_inverse = u32::from_le_bytes(blob[4..8].try_into().unwrap());
        assert_eq!(n0.wrapping_mul(n0_inverse), u32::MAX);
        let rr = BigUint::from_bytes_le(&blob[8 + MODULUS_BYTES..8 + MODULUS_BYTES * 2]);
        assert_eq!(rr, BigUint::from(1u32));

        for odd in [3u32, 5, 0x1234_5679, 0xffff_ffff] {
            assert_eq!(
                odd.wrapping_mul(negative_inverse_mod_2_32(odd)),
                u32::MAX
            );
        }
    }

    #[test]
    fn rejects_invalid_modulus_exponent_and_key_name() {
        let modulus = [0xffu8; MODULUS_BYTES];
        assert!(AdbPublicKey::from_rsa_components(&modulus[..255], &[1, 0, 1], "host").is_err());
        let mut even = modulus;
        even[255] = 0xfe;
        assert!(AdbPublicKey::from_rsa_components(&even, &[1, 0, 1], "host").is_err());
        assert!(AdbPublicKey::from_rsa_components(&modulus, &[2], "host").is_err());
        assert!(AdbPublicKey::from_rsa_components(&modulus, &[1, 0, 1], "bad\nname").is_err());
    }
}
