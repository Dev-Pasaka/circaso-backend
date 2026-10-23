# Shared by the port 80 and port 443 servers (included inside each `server` block).

server_tokens off;

# Docker's own resolver, so a restarted app container is found again at its new address.
resolver 127.0.0.11 valid=10s ipv6=off;
set $upstream app:8080;

client_max_body_size 12m;
client_body_timeout 15s;
client_header_timeout 15s;
send_timeout 30s;
limit_conn perip 40;

add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "no-referrer" always;
add_header Strict-Transport-Security "max-age=31536000" always;

# Not for the public: readiness reveals which dependencies are down, and Kandra's health route lists the database.
location = /health/ready { return 404; }
location ^~ /kandra/ { return 404; }
location ^~ /dev/ { return 404; }

location / {
    limit_req zone=api burst=60 nodelay;

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
}
