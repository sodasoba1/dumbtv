//! Pure DumbTV home.conf transformation for the optional SmartTube workflow.
//! This module never reads or writes device files; callers must retain the exact
//! original bytes before applying the returned replacement.

pub const MAX_HOME_CONFIG_BYTES: usize = 256 * 1024;

#[allow(dead_code)] // Wired only when the YouTube backup/restore workflow is complete.
pub fn rewrite_apps_order(original: &[u8]) -> Result<Vec<u8>, String> {
    if original.len() > MAX_HOME_CONFIG_BYTES {
        return Err("DumbTV home.conf exceeds the 256 KiB safety limit".into());
    }
    let text = std::str::from_utf8(original)
        .map_err(|_| "DumbTV home.conf is not valid UTF-8".to_owned())?;
    let mut output = String::with_capacity(text.len() + 64);
    let mut found = false;

    for line in text.split_inclusive('\n') {
        let (body, ending) = if let Some(body) = line.strip_suffix("\r\n") {
            (body, "\r\n")
        } else if let Some(body) = line.strip_suffix('\n') {
            (body, "\n")
        } else {
            (line, "")
        };
        if body.starts_with("apps.order=") {
            if found {
                return Err("DumbTV home.conf contains duplicate apps.order keys".into());
            }
            found = true;
            let mut apps = Vec::new();
            for item in body["apps.order=".len()..].split(',') {
                let package = item.trim();
                if package.is_empty()
                    || package == crate::apk_release::SMARTTUBE_PACKAGE
                    || package == crate::apk_release::STOCK_YOUTUBE_PACKAGE
                    || apps
                        .iter()
                        .any(|existing: &String| existing.as_str() == package)
                {
                    continue;
                }
                if package.len() > 255
                    || !package
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_'))
                {
                    return Err(
                        "DumbTV home.conf contains an invalid package entry in apps.order".into(),
                    );
                }
                apps.push(package.to_owned());
            }
            let mut order = vec![crate::apk_release::SMARTTUBE_PACKAGE.to_owned()];
            order.extend(apps);
            output.push_str("apps.order=");
            output.push_str(&order.join(","));
            output.push_str(ending);
        } else {
            output.push_str(line);
        }
    }

    if !found {
        if !output.is_empty() && !output.ends_with('\n') {
            output.push('\n');
        }
        output.push_str("apps.order=");
        output.push_str(crate::apk_release::SMARTTUBE_PACKAGE);
        output.push('\n');
    }
    Ok(output.into_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replaces_stock_youtube_and_keeps_other_home_order_entries() {
        let original = b"# user settings\napps.order=com.amazon.firetv.youtube,com.example.netflix,org.smarttube.stable,com.example.other\nlayout=compact\n";
        let updated = String::from_utf8(rewrite_apps_order(original).unwrap()).unwrap();
        assert!(updated
            .contains("apps.order=org.smarttube.stable,com.example.netflix,com.example.other\n"));
        assert!(updated.contains("layout=compact\n"));
        assert!(updated.contains("# user settings\n"));
        assert!(!updated.contains("com.amazon.firetv.youtube"));
        assert_eq!(updated.matches("apps.order=").count(), 1);
    }

    #[test]
    fn adds_apps_order_when_missing_and_preserves_crlf() {
        let original = b"layout=compact\r\n";
        let updated = rewrite_apps_order(original).unwrap();
        assert_eq!(
            updated,
            b"layout=compact\r\napps.order=org.smarttube.stable\n"
        );
    }

    #[test]
    fn rejects_duplicate_or_invalid_apps_order_entries() {
        assert!(rewrite_apps_order(b"apps.order=a.b\napps.order=c.d\n").is_err());
        assert!(rewrite_apps_order(b"apps.order=valid.package,not valid\n").is_err());
        assert!(rewrite_apps_order(&vec![b'x'; MAX_HOME_CONFIG_BYTES + 1]).is_err());
        assert!(rewrite_apps_order(&[0xff, 0xfe]).is_err());
    }
}
