# What every proxied request to the app carries. Included inside a location.
proxy_pass http://$upstream;
proxy_http_version 1.1;
proxy_set_header Host $host;
# After real_ip, $remote_addr is the caller's true address when the request came through Cloudflare, and the direct peer otherwise.
# The app trusts this header only because nginx sets it, replacing anything the caller sent.
proxy_set_header CF-Connecting-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;

# Asha streams its answer (server-sent events) and the realtime socket stays open: neither may be buffered or cut short.
proxy_buffering off;
proxy_read_timeout 3600s;
proxy_send_timeout 60s;
proxy_connect_timeout 5s;
