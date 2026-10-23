#!/usr/bin/env bash
# Rewrites cloudflare-ips.inc from Cloudflare's published ranges. Run it from the repository root, commit the result, deploy.
set -euo pipefail
out="deploy/nginx/cloudflare-ips.inc"
{
  echo "# Cloudflare's published address ranges (https://www.cloudflare.com/ips). Only a request coming from one of these may tell us the caller's real"
  echo "# address through CF-Connecting-IP; from anywhere else the header is ignored. Refresh occasionally: deploy/nginx/refresh-cloudflare-ips.sh"
  curl -fsS https://www.cloudflare.com/ips-v4 | sed 's/.*/set_real_ip_from &;/'
  echo
  curl -fsS https://www.cloudflare.com/ips-v6 | sed 's/.*/set_real_ip_from &;/'
  echo "real_ip_header CF-Connecting-IP;"
} > "$out"
echo "wrote $out"
