# TLS 证书目录

`tls.crt` / `tls.key` 由部署机本地生成，不入版本库。生成命令见上级 README「5. TLS 证书」。
目录必须存在，否则 compose 的只读挂载会失败。
