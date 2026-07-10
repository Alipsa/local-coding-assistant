#!/usr/bin/env bash
# Shared helpers for demo/test_*.sh scripts.

pass=0
fail=0

check() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "PASS: $desc"
    pass=$((pass + 1))
  else
    echo "FAIL: $desc (expected [$expected], got [$actual])"
    fail=$((fail + 1))
  fi
}

# make_stub <bin_dir> <name> <exit_code> <log_file>
# Writes an executable <bin_dir>/<name> that appends "<name> $*" to
# <log_file> (one call per line) and exits with <exit_code>.
make_stub() {
  local bin_dir="$1" name="$2" exit_code="$3" log_file="$4"
  cat > "$bin_dir/$name" <<EOF
#!/usr/bin/env bash
echo "$name \$*" >> "$log_file"
exit $exit_code
EOF
  chmod +x "$bin_dir/$name"
}

report() {
  echo "---"
  echo "$pass passed, $fail failed"
  [[ $fail -eq 0 ]]
}
