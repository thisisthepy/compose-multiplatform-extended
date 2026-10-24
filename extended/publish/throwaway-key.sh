#!/usr/bin/env bash
# Makes a signing key that exists only for one job, in the GNUPGHOME given, for the dry run
# and the tests. It has no passphrase and expires in a day; nothing signed with it is ever
# uploaded.
#
# Usage: throwaway-key.sh <gnupg home>
set -euo pipefail
home="${1:?usage: throwaway-key.sh <gnupg home>}"
mkdir -p "$home"
chmod 700 "$home"
GNUPGHOME="$home" gpg --batch --quiet --passphrase '' \
    --quick-generate-key "thisisthepy dry run <dry-run@invalid>" rsa3072 sign 1d
GNUPGHOME="$home" gpg --batch --list-secret-keys --with-colons | awk -F: '$1 == "fpr" { print $10; exit }'
