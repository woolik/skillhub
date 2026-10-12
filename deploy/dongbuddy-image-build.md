# Dongbuddy 内部镜像构建与部署手册

面向需要反复构建 SkillHub 修改版镜像（含 Dongbuddy 免登打通）的同学。
一键脚本：`scripts/build-release-images.sh`（默认 tag `latest`，产出
`skillhub-server.tar` / `skillhub-web.tar`）。本文记录脚本之外的约定与坑。

## 1. 构建

```bash
scripts/build-release-images.sh                 # 测试 + 构建 + 导出 tar
scripts/build-release-images.sh --tag 0.2.47    # 指定 tag
scripts/build-release-images.sh --skip-tests    # 赶时间跳过单测
```

- build context：server 镜像 = `server/`，web 镜像 = `web/`（**不是仓库根**）。
- 本机无需 JVM/Maven：单测与构建全部在容器内完成
  （`eclipse-temurin:21-jdk-alpine` / `node:22-alpine` + `nginx:alpine`）。
- 首建 server 镜像需下载 maven 依赖，约 10–20 分钟；web 约数分钟。
- `server/.dockerignore` 已排除 `**/target/`，宿主机残留 target 不污染镜像。

## 2. 免登开关与密钥

| 变量 | 位置 | 说明 |
|---|---|---|
| `SKILLHUB_AUTH_SESSION_BOOTSTRAP_ENABLED` | **server 镜像 ENV 已烘焙 =true**；compose 默认 true | `skillhub.auth.session-bootstrap.enabled` |
| `SKILLHUB_AUTH_DONGBUDDY_ENABLED` | **server 镜像 ENV 已烘焙 =true**；compose 默认 true | `skillhub.auth.dongbuddy.enabled` |
| `SKILLHUB_AUTH_DONGBUDDY_SECRET` | **仅运行时注入，永不入镜像** | 必须等于 Dongbuddy 客户端内置钥 `BUILTIN_SKILLHUB_SSO_SECRET`（kunbuddy-ultra `apps/engine-gateway/src/skillhubConfig.ts`）；缺失时 provider fail-closed |
| `SKILLHUB_AUTH_DONGBUDDY_PREVIOUS_SECRET` | 仅运行时注入，可选 | 轮换窗口内同时接受旧钥 |

**客户端零配置**：Dongbuddy 是 Win/Mac 分发的客户端，终端用户不设任何 env；
共享钥随 gateway bundle 编译分发（明文 JS 可提取 ≈ 内部公开钥强度，靠限流 +
nonce 单次 + 审计 + 轮换控制半径）。`SKILLHUB_SSO_SECRET` / `SKILLHUB_BASE_URL`
在 gateway 侧仅作**开发覆盖**。

验证烘焙：`docker run --rm --entrypoint sh skillhub-server:latest -c 'env | grep SKILLHUB_AUTH_'`。

### 密钥轮换 runbook

1. 生成新钥；SkillHub 部署设 `SKILLHUB_AUTH_DONGBUDDY_SECRET=<new>`、
   `SKILLHUB_AUTH_DONGBUDDY_PREVIOUS_SECRET=<old>`（重启生效，无需重新打镜像）；
2. kunbuddy-ultra 更新 `BUILTIN_SKILLHUB_SSO_SECRET=<new>`，发新客户端；
3. 旧客户端基本升级完毕后，清空 `SKILLHUB_AUTH_DONGBUDDY_PREVIOUS_SECRET`。

## 3. 部署（目标机）

```bash
docker load -i skillhub-server.tar && docker load -i skillhub-web.tar
SKILLHUB_SERVER_IMAGE=skillhub-server SKILLHUB_WEB_IMAGE=skillhub-web SKILLHUB_VERSION=latest \
SKILLHUB_AUTH_DONGBUDDY_SECRET=<同 gateway 的 SKILLHUB_SSO_SECRET> \
docker compose -f compose.release.yml up -d
```

compose 通过 `SKILLHUB_SERVER_IMAGE/SKILLHUB_WEB_IMAGE/SKILLHUB_VERSION` 替换默认
ghcr 镜像；**compose 的 environment 显式设值会覆盖镜像 ENV**，故 compose 里两个
开关默认值也已是 true（见 compose.release.yml server.environment）。

## 4. 本地 e2e / smoke（可选但推荐）

```bash
SKILLHUB_SERVER_IMAGE=skillhub-server SKILLHUB_WEB_IMAGE=skillhub-web SKILLHUB_VERSION=latest \
SKILLHUB_AUTH_DONGBUDDY_SECRET=e2e-test-secret SKILLHUB_SECURITY_SCANNER_ENABLED=false \
SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET=e2e-anon-cookie-secret-0123456789abcdef \
docker compose -f compose.release.yml -f deploy/local-e2e-no-scanner.override.yml up -d server web
```

冒烟序列（CSRF → 签票 → 免登 → 列表）：

```bash
curl -s -c cj.txt -o /dev/null localhost:8080/api/v1/auth/providers        # 取 XSRF-TOKEN
XSRF=$(python3 -c 'import urllib.parse;print(urllib.parse.unquote("'<$(awk '$6=="XSRF-TOKEN"{print $7}' cj.txt)>'"))')
TICKET=$(node -e '/* HMAC-SHA256(secret, base64url(payload)) 见 gateway skillhubClient.signSsoTicket */')
curl -i -b cj.txt -c cj.txt -X POST localhost:8080/api/v1/auth/session/bootstrap \
  -H "X-XSRF-TOKEN: $XSRF" -H "X-Dongbuddy-Sso: $TICKET" \
  -H 'Content-Type: application/json' -d '{"provider":"dongbuddy"}'        # 期望 200 + userId=kb-…
curl -s -b cj.txt 'localhost:8080/api/web/skills'                        # 期望 200（全部可见：global + 成员空间）
# 结束后拆栈：
docker compose -f compose.release.yml -f deploy/local-e2e-no-scanner.override.yml down -v
```

期望：bootstrap 200（首次自动建 user 并加入 global；团队空间由管理员添加）；同票重用 401（nonce 单次）；
无票/坏签 401。

## 5. 踩过的坑

1. **`SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET` 必填且 ≥32 字符**，否则 server 启动即崩
   （`AnonymousDownloadIdentityService` init 校验）——compose 不会给默认值。
2. **skill-scanner 无 LLM key 起不来**且 server `depends_on` 要求它 healthy：本地用
   `deploy/local-e2e-no-scanner.override.yml` 降级为 service_started。compose 的
   depends_on 是**合并不是替换**，override 里删不掉 key，只能改 condition。
3. **bootstrap POST 受 CSRF 保护**（CookieCsrfTokenRepository，不在豁免清单）：
   服务端调用方必须先 GET 任意 permit-all 接口取 `XSRF-TOKEN` cookie，再回显
   `X-XSRF-TOKEN` 头（Dongbuddy gateway `skillhubClient.fetchCsrfToken` 已实现）。
4. 匿名请求被 CSRF/认证拒绝时表现为 **401 而非 403**（Spring entry point 转换），
   排障时别误判为票据问题。
5. web 镜像**无需任何新 env**：`/sso/dongbuddy` 是纯前端换票页，走既有
   `authApi.bootstrapSession`；只有 server 需要开关与密钥。
6. **清理开源内置技能（@global 13 个社区技能）**：启动播种开关
   `SKILLHUB_BUILTIN_SKILLS_ENABLED`（本 compose 默认已改 false）。**先**设 false
   重启（否则删除后重启又被播回来），**再**以 SUPER_ADMIN 会话逐个硬删：
   `DELETE /api/web/skills/global/<slug>`（带 X-XSRF-TOKEN；硬删含索引与存储文件）。
   名单：exam-ready weather time-blocking-scheduler study-strategy-selector
   storytelling-advisor retrieval-practice-generator meeting-note-summarizer
   linkedin-post-formatter frontend-design documentation-writer
   daily-standup-journal ai-claim-checker agentguard。
   想可逆则改用 `POST /api/v1/admin/skills/{id}/hide`（仅隐藏不删除）。
7. **发布 500「Failed to process files」= 存储目录权限**：server 以 `USER app`
   运行，而 `/var/lib/skillhub/storage` 挂载卷若由旧版 root 镜像创建则属主为
   root → `LocalFileStorageService.putObject` 抛 AccessDeniedException。
   修复：`docker exec <server> id` 取 uid:gid 后
   `docker run --rm -v <storage卷>:/data --user root alpine chown -R <uid>:<gid> /data`
   （bind mount 则在宿主机 chown）；无需重打镜像。
