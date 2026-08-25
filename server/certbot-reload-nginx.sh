#!/bin/bash
# certbot deploy hook: reload nginx after a successful certificate renewal.
# The live web server is the BT panel nginx (/etc/init.d/nginx);
# fall back to systemd nginx if the init script is unavailable.
if [ -x /etc/init.d/nginx ]; then
  /etc/init.d/nginx reload
else
  nginx -t && systemctl reload nginx
fi
