//! Addresses of pages that hold a video, not of files: Add URL hands these to the video window
//! (yt-dlp) instead of downloading the page itself. Only addresses whose shape says "one video"
//! are listed — a channel, a search or a home page is left alone. Any other address that turns
//! out to be a web page is offered to the video window after the probe (Add URL).

use reqwest::Url;

/// Whether `url` is a video's page on a site yt-dlp reads (YouTube, Vimeo, Dailymotion, Twitch,
/// TikTok, X, Instagram, Facebook, Threads, Reddit, SoundCloud, Bilibili, Rumble).
pub fn is_video_page(url: &Url) -> bool {
    if !matches!(url.scheme(), "http" | "https") {
        return false;
    }
    let Some(host) = url.host_str() else {
        return false;
    };
    let host = host.to_ascii_lowercase();
    let host = host
        .strip_prefix("www.")
        .or_else(|| host.strip_prefix("m."))
        .unwrap_or(&host);
    let path = url.path();
    // A stream's own address — an HLS playlist (`.m3u8`) or DASH's (`.mpd`) — is a video page in
    // this sense: yt-dlp reads the playlist and its qualities, and what is downloaded is merged
    // into one playable file. Pasting a playlist used to download the playlist itself.
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".m3u8") || lower.ends_with(".mpd") {
        return true;
    }
    let segs: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
    let query = |k: &str| url.query_pairs().any(|(key, v)| key == k && !v.is_empty());
    match host {
        "youtube.com" | "music.youtube.com" | "youtube-nocookie.com" => {
            (path == "/watch" && query("v"))
                // A playlist's own page: the video window lists its videos (#69, #338).
                || (path == "/playlist" && query("list"))
                || matches!(segs.as_slice(), ["shorts" | "live" | "embed" | "v", _, ..])
        }
        "youtu.be" => segs.len() == 1,
        "vimeo.com" => segs.iter().any(|s| s.chars().all(|c| c.is_ascii_digit())),
        "player.vimeo.com" => matches!(segs.as_slice(), ["video", _, ..]),
        "dailymotion.com" => matches!(segs.as_slice(), ["video", _, ..]),
        "geo.dailymotion.com" => segs.first() == Some(&"player") && query("video"),
        "dai.ly" => segs.len() == 1,
        "twitch.tv" => matches!(segs.as_slice(), ["videos", _] | [_, "clip", _]),
        "clips.twitch.tv" => segs.len() == 1,
        // A post's own address, and the short links the apps' Share buttons copy.
        "tiktok.com" => matches!(segs.as_slice(), [_, "video", _] | ["t", _]),
        "vm.tiktok.com" | "vt.tiktok.com" => segs.len() == 1,
        "x.com" | "twitter.com" | "mobile.twitter.com" | "mobile.x.com" => {
            matches!(segs.as_slice(), [_, "status", _, ..])
        }
        "instagram.com" => matches!(
            segs.as_slice(),
            ["reel" | "reels" | "p" | "tv", _, ..] | [_, "reel" | "p", _, ..]
        ),
        "facebook.com" | "web.facebook.com" => {
            (path == "/watch" || path == "/watch/") && query("v")
                || matches!(
                    segs.as_slice(),
                    [_, "videos", ..] | ["reel", _, ..] | ["share", "v" | "r", _, ..]
                )
        }
        "fb.watch" => segs.len() == 1,
        "threads.net" | "threads.com" => {
            matches!(segs.as_slice(), [user, "post", _, ..] if user.starts_with('@'))
        }
        "reddit.com" | "old.reddit.com" => matches!(segs.as_slice(), ["r", _, "comments", _, ..]),
        "v.redd.it" => segs.len() == 1,
        "soundcloud.com" => segs.len() == 2,
        "bilibili.com" => matches!(segs.as_slice(), ["video", _, ..]),
        "rumble.com" => segs
            .first()
            .is_some_and(|s| s.starts_with('v') && s.contains('-')),
        _ => false,
    }
}

/// Whether `url` is a post on a social site (TikTok, X, Instagram, Facebook, Threads) that holds
/// a video: Add URL starts these at once in the best quality, without the quality window, when
/// Download video's "Start social posts at once" is ticked. A post is one video, never a list.
pub fn is_social_post(url: &Url) -> bool {
    let social = url.host_str().is_some_and(|host| {
        let host = host.to_ascii_lowercase();
        [
            "tiktok.com",
            "x.com",
            "twitter.com",
            "instagram.com",
            "facebook.com",
            "fb.watch",
            "threads.net",
            "threads.com",
        ]
        .iter()
        .any(|site| host == *site || host.ends_with(&format!(".{site}")))
    });
    social && is_video_page(url)
}

/// The address to give yt-dlp for a video page: Dailymotion's embedded player
/// (`geo.dailymotion.com/player/<player>.html?video=<id>`, what a site embedding it shows) becomes
/// the video's own page, which is the one that always lists its formats (#359). Any other address
/// as it is.
pub fn video_address(page: &str) -> String {
    let id = Url::parse(page).ok().and_then(|url| {
        let player = url
            .host_str()
            .is_some_and(|h| h.eq_ignore_ascii_case("geo.dailymotion.com"))
            && url.path().starts_with("/player");
        player
            .then(|| {
                url.query_pairs()
                    .find(|(k, _)| k == "video")
                    .map(|(_, v)| v.into_owned())
            })
            .flatten()
    });
    match id {
        Some(id) if !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric()) => {
            format!("https://www.dailymotion.com/video/{id}")
        }
        _ => page.to_owned(),
    }
}

/// Whether `url` is an image gallery's page on a site gallery-dl reads (#58): a Wikimedia
/// Commons category, a Flickr album or photographer, a DeviantArt or ArtStation gallery, an
/// Imgur album, a Pixiv artist or work, a Pinterest board, a Danbooru or Gelbooru search, a
/// Tumblr blog, a Bluesky profile. A picture's own address (a file) is not one. gallery-dl reads
/// many more; this list is what Add URL sends to its window without asking it first.
pub fn is_gallery_page(url: &Url) -> bool {
    if !matches!(url.scheme(), "http" | "https") {
        return false;
    }
    let Some(host) = url.host_str() else {
        return false;
    };
    let host = host.to_ascii_lowercase();
    let host = host.strip_prefix("www.").unwrap_or(&host);
    let segs: Vec<&str> = url.path().split('/').filter(|s| !s.is_empty()).collect();
    // A file's own address is downloaded as a file.
    if segs.last().is_some_and(|s| {
        let s = s.to_ascii_lowercase();
        [
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp", ".tif", ".tiff", ".svg",
        ]
        .iter()
        .any(|e| s.ends_with(e))
    }) {
        return false;
    }
    let query = |k: &str| url.query_pairs().any(|(key, v)| key == k && !v.is_empty());
    match host {
        "commons.wikimedia.org" => {
            matches!(segs.as_slice(), ["wiki", p] if p.starts_with("Category:"))
        }
        "flickr.com" => matches!(segs.as_slice(), ["photos", _, ..]),
        "deviantart.com" => matches!(segs.as_slice(), [_, "gallery", ..]),
        h if h.ends_with(".deviantart.com") => true,
        "artstation.com" => matches!(segs.as_slice(), [_] | ["artwork", _]),
        "imgur.com" => matches!(segs.as_slice(), ["a" | "gallery", _]),
        "pixiv.net" => matches!(
            segs.as_slice(),
            ["users", _, ..] | ["artworks", _] | [_, "users" | "artworks", _, ..]
        ),
        // A board (`/user/board/`) or a pin (`/pin/id/`).
        "pinterest.com" => segs.len() == 2,
        "danbooru.donmai.us" | "safebooru.donmai.us" => {
            matches!(segs.as_slice(), ["posts"]) && query("tags")
        }
        "gelbooru.com" => query("tags"),
        h if h.ends_with(".tumblr.com") => true,
        "bsky.app" => matches!(segs.as_slice(), ["profile", _, ..]),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn page(s: &str) -> bool {
        is_video_page(&s.parse().unwrap())
    }

    fn gallery(s: &str) -> bool {
        is_gallery_page(&s.parse().unwrap())
    }

    #[test]
    fn a_streams_playlist_is_a_video_page() {
        assert!(page("https://cdn.example/hls/master.m3u8"));
        assert!(page("https://cdn.example/v/index.M3U8?token=abc"));
        assert!(page("https://cdn.example/movie/manifest.mpd"));
        // A plain file, or a scheme no video is fetched over.
        assert!(!page("https://cdn.example/files/readme.txt"));
        assert!(!page("ftp://cdn.example/hls/master.m3u8"));
    }

    #[test]
    fn gallery_pages_are_recognised() {
        assert!(gallery(
            "https://commons.wikimedia.org/wiki/Category:Featured_pictures_of_birds"
        ));
        assert!(gallery(
            "https://www.flickr.com/photos/nasawebbtelescope/albums/72177720313923911"
        ));
        assert!(gallery("https://www.flickr.com/photos/nasawebbtelescope/"));
        assert!(gallery("https://www.deviantart.com/someone/gallery"));
        assert!(gallery("https://imgur.com/a/abc123"));
        assert!(gallery("https://www.pixiv.net/en/users/123456"));
        assert!(gallery("https://danbooru.donmai.us/posts?tags=landscape"));
        assert!(gallery("https://bsky.app/profile/nasa.gov"));
        // A page that's no gallery, a picture's own address, a video page.
        assert!(!gallery("https://commons.wikimedia.org/wiki/Main_Page"));
        assert!(!gallery(
            "https://upload.wikimedia.org/wikipedia/commons/9/9d/Rhea.jpg"
        ));
        assert!(!gallery(
            "https://www.flickr.com/photos/x/123/in/album/a.JPG"
        ));
        assert!(!gallery("https://www.youtube.com/watch?v=Tg6byXUo8RU"));
        assert!(!gallery("ftp://commons.wikimedia.org/wiki/Category:Birds"));
    }

    #[test]
    fn video_pages_are_recognised() {
        // Reported 2026-10-29: a watch page from a mix (list=RD…, start_radio) — one video.
        assert!(page(
            "https://www.youtube.com/watch?v=Tg6byXUo8RU&list=RDTg6byXUo8RU&start_radio=1"
        ));
        assert!(page("https://youtube.com/watch?v=Tg6byXUo8RU"));
        assert!(page("https://m.youtube.com/watch?v=Tg6byXUo8RU&t=42s"));
        assert!(page("https://music.youtube.com/watch?v=Tg6byXUo8RU"));
        assert!(page("https://www.youtube.com/shorts/abcDEF12345"));
        assert!(page("https://youtu.be/Tg6byXUo8RU?si=x"));
        assert!(page("https://vimeo.com/76979871"));
        assert!(page("https://www.dailymotion.com/video/x8abcd"));
        assert!(page(
            "https://geo.dailymotion.com/player/x19jsm.html?video=k4lGbCpdJJFSQgMvE8W"
        ));
        assert!(page("https://www.youtube.com/playlist?list=PLS37ZBSRa46"));
        assert!(page("https://www.twitch.tv/videos/123456"));
        assert!(page(
            "https://www.tiktok.com/@someone/video/7300000000000000000"
        ));
        assert!(page("https://x.com/someone/status/1800000000000000000"));
        assert!(page("https://www.instagram.com/reel/Cabc123/"));
        assert!(page(
            "https://www.reddit.com/r/videos/comments/abc123/title/"
        ));
        assert!(page("https://soundcloud.com/artist/track"));
    }

    #[test]
    fn social_posts_and_their_share_links_are_recognised() {
        let social = |s: &str| is_social_post(&s.parse().unwrap());
        for post in [
            "https://www.tiktok.com/@someone/video/7300000000000000000",
            "https://vm.tiktok.com/ZMabc123/",
            "https://vt.tiktok.com/ZSabc123/",
            "https://www.tiktok.com/t/ZTabc123/",
            "https://x.com/someone/status/1800000000000000000",
            "https://twitter.com/someone/status/1800000000000000000/video/1",
            "https://mobile.twitter.com/someone/status/1800000000000000000",
            "https://www.instagram.com/reel/Cabc123/",
            "https://www.instagram.com/p/Cabc123/?igsh=xyz",
            "https://www.instagram.com/someone/reel/Cabc123/",
            "https://www.facebook.com/watch/?v=1234567890",
            "https://www.facebook.com/someone/videos/1234567890/",
            "https://www.facebook.com/reel/1234567890",
            "https://www.facebook.com/share/v/1AbCdEf/",
            "https://www.facebook.com/share/r/1AbCdEf/",
            "https://m.facebook.com/watch/?v=1234567890",
            "https://fb.watch/abcDEF/",
            "https://www.threads.net/@someone/post/C8abc123",
            "https://www.threads.com/@someone/post/C8abc123",
        ] {
            assert!(social(post), "{post}");
        }
        for not in [
            // A profile, a feed, a home page: no one video.
            "https://www.tiktok.com/@someone",
            "https://x.com/someone",
            "https://www.instagram.com/someone/",
            "https://www.facebook.com/",
            "https://www.threads.net/@someone",
            // A video site that isn't social: the quality window, as before.
            "https://www.youtube.com/watch?v=Tg6byXUo8RU",
            "https://vimeo.com/76979871",
            // Only lookalike hosts.
            "https://notx.com/someone/status/1",
            "https://tiktok.com.example/@a/video/1",
        ] {
            assert!(!social(not), "{not}");
        }
    }

    #[test]
    fn dailymotions_player_becomes_the_videos_own_page() {
        assert_eq!(
            video_address(
                "https://geo.dailymotion.com/player/x19jsm.html?video=k4lGbCpdJJFSQgMvE8W"
            ),
            "https://www.dailymotion.com/video/k4lGbCpdJJFSQgMvE8W"
        );
        for same in [
            "https://geo.dailymotion.com/player/x19jsm.html",
            "https://geo.dailymotion.com/player/x19jsm.html?video=a/b",
            "https://www.dailymotion.com/video/x8abcd",
            "https://www.youtube.com/watch?v=x",
            "not an address",
        ] {
            assert_eq!(video_address(same), same);
        }
    }

    #[test]
    fn other_pages_and_files_are_not() {
        assert!(!page("https://www.youtube.com/"));
        assert!(!page("https://www.youtube.com/watch"));
        assert!(!page("https://www.youtube.com/@channel/videos"));
        assert!(!page("https://www.youtube.com/results?search_query=x"));
        assert!(!page("https://www.youtube.com/playlist"));
        assert!(!page("https://geo.dailymotion.com/player/x19jsm.html"));
        assert!(!page("https://vimeo.com/channels/staffpicks"));
        assert!(!page("https://example.com/video.mp4"));
        assert!(!page("https://x.com/someone"));
        assert!(!page("ftp://youtube.com/watch?v=x"));
    }
}
