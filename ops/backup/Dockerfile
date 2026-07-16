FROM alpine:3.20
RUN apk add --no-cache bash mysql-client jq tar coreutils ca-certificates restic
COPY *.sh /usr/local/bin/
RUN chmod 700 /usr/local/bin/*.sh
