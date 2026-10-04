use std::io::Write;
use velopack::sources::FileSource;
use velopack::*;

const VERSION: &str = match option_env!("PROBE_VERSION") { Some(v) => v, None => "dev" };

fn log(msg: &str) {
    let dir = std::env::var("PROBE_LOG").unwrap_or_else(|_| "/tmp/probe.log".into());
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(dir) {
        let _ = writeln!(f, "[{}] pid={} {}", VERSION, std::process::id(), msg);
    }
}

fn main() {
    VelopackApp::build().run();
    let args: Vec<String> = std::env::args().collect();
    log(&format!("started version={} exe={:?} args={:?}", VERSION, std::env::current_exe().ok(), &args[1..]));
    let feed = std::env::var("PROBE_FEED").ok();
    if let Some(feed) = feed {
        if VERSION == "1.0.1" { log("already newest, exiting 0"); return; }
        let um = match UpdateManager::new(FileSource::new(&feed), None, None) {
            Ok(u) => u,
            Err(e) => { log(&format!("UpdateManager::new failed: {e:?}")); std::process::exit(10); }
        };
        log(&format!("manager ok, installed version {:?}", um.get_current_version_as_string()));
        match um.check_for_updates() {
            Ok(UpdateCheck::UpdateAvailable(u)) => {
                log("update available, downloading");
                if let Err(e) = um.download_updates(&u, None) { log(&format!("download failed: {e:?}")); std::process::exit(11); }
                log("downloaded, applying");
                let r = um.apply_updates_and_restart(&u);
                log(&format!("apply returned: {:?}", r.err()));
                std::process::exit(12);
            }
            Ok(_) => { log("no update"); std::process::exit(13); }
            Err(e) => { log(&format!("check failed: {e:?}")); std::process::exit(14); }
        }
    }
}
