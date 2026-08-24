#!/usr/bin/env bash
# After live-lab-boot.sh: start puppet loop in background, then tmate (or puppet-only).
set -euo pipefail

MODE="${LAB_MODE:-both}"
HOLD_SECS="${LAB_HOLD_SECS:-12000}"
REPO_DIR="${GITHUB_WORKSPACE:-$PWD}"
LAB_DIR="${LAB_OUT:-$PWD/lab-out}"
mkdir -p "$LAB_DIR"

puppet_loop() {
  WORK=/tmp/lab-control-git
  rm -rf "$WORK"
  AUTH_URL="https://github.com/asmoia/arya-agent.git"
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    AUTH_URL="https://x-access-token:${GITHUB_TOKEN}@github.com/asmoia/arya-agent.git"
  fi
  git clone --branch lab-control --depth 8 "$AUTH_URL" "$WORK" || {
    git clone --depth 1 "$AUTH_URL" "$WORK"
    cd "$WORK"
    git checkout -B lab-control
    mkdir -p lab
    echo 'echo puppet-ready' > lab/command.sh
    echo 'idle' > lab/output.txt
    git add lab
    git -c user.email=lab@arya.local -c user.name='Arya Live Lab' commit -m "lab-control init"
    git push -u origin lab-control || true
  }
  cd "$WORK"
  git config user.email "lab@arya.local"
  git config user.name "Arya Live Lab"
  LAST=""
  i=0
  echo "PUPPET_LOOP_START"
  while [ "$i" -lt 2000 ]; do
    i=$((i+1))
    git fetch origin lab-control >/dev/null 2>&1 || true
    git checkout -f origin/lab-control -- lab/command.sh 2>/dev/null || true
    if [ -s lab/command.sh ]; then
      SUM=$(md5sum lab/command.sh | awk '{print $1}')
      if [ "$SUM" != "$LAST" ]; then
        echo "PUPPET_RUN $SUM"
        # Commands run against the runner workspace (emulator + built tree).
        set +e
        ( cd "$REPO_DIR" && bash "$WORK/lab/command.sh" ) > "$WORK/lab/output.txt" 2>&1
        EC=$?
        set -e
        echo "PUPPET_EXIT=$EC" >> "$WORK/lab/output.txt"
        date -u +"PUPPET_TS=%FT%TZ" >> "$WORK/lab/output.txt"
        LAST=$SUM
        git add lab/output.txt
        git commit -m "lab: output $(date -u +%H%M%S) exit=$EC" || true
        git push origin HEAD:lab-control || true
      fi
    fi
    sleep 5
  done
}

if [ "$MODE" = "puppet" ] || [ "$MODE" = "both" ]; then
  puppet_loop > "$LAB_DIR/puppet.log" 2>&1 &
  echo $! > "$LAB_DIR/puppet.pid"
  echo "PUPPET_PID=$(cat "$LAB_DIR/puppet.pid")"
fi

if [ "$MODE" = "tmate" ] || [ "$MODE" = "both" ]; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq tmate openssh-client
  tmate -S /tmp/tmate.sock new-session -d
  tmate -S /tmp/tmate.sock wait tmate-ready
  echo "========== TMATE =========="
  tmate -S /tmp/tmate.sock display -p 'TMATE_SSH=#{tmate_ssh}'
  tmate -S /tmp/tmate.sock display -p 'TMATE_SSH_RO=#{tmate_ssh_ro}'
  tmate -S /tmp/tmate.sock display -p 'TMATE_WEB=#{tmate_web}'
  tmate -S /tmp/tmate.sock display -p 'TMATE_WEB_RO=#{tmate_web_ro}'
  echo "========== /TMATE =========="
  # also dump to a file the artifact uploader can grab early
  {
    tmate -S /tmp/tmate.sock display -p 'TMATE_SSH=#{tmate_ssh}'
    tmate -S /tmp/tmate.sock display -p 'TMATE_WEB=#{tmate_web}'
  } | tee "$LAB_DIR/tmate.txt"
fi

echo "LAB_HOLD ${HOLD_SECS}s mode=$MODE"
# Keep emulator-runner script (and therefore the emulator) alive.
end=$(( $(date +%s) + HOLD_SECS ))
while [ "$(date +%s)" -lt "$end" ]; do
  if [ -f /tmp/tmate.sock ]; then
    tmate -S /tmp/tmate.sock display -p 'TMATE_SSH=#{tmate_ssh}' || true
  fi
  adb shell echo lab-alive >/dev/null 2>&1 || echo "adb-lost"
  sleep 30
done
echo "LAB_HOLD_DONE"
