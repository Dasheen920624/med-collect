# 医疗文献采集系统 Docker 部署与更新

## 部署配置

| 项目 | 配置 |
| --- | --- |
| 入口 | `MEDCOLLECT_PUBLISH_HOST` 指定的地址，默认端口 18085 |
| 部署目录 | 本文以 `/zoesoft/med-collect` 为例 |
| 运行方式 | 以 Docker 26 和 Compose 2.27 验收，项目 `med-collect`，服务 `app` |
| 应用镜像 | `med-collect:<发行标识>`，由 `release.env` 指定 |
| 运行时 | Eclipse Temurin JRE 21，容器 UID/GID `10001:10001` |
| 应用配置 | `/zoesoft/med-collect/config/app.env`，`root:root`、`0600` |
| 数据库 | Oracle profile，从 `MEDCOLLECT_DB_URL`、`MEDCOLLECT_DB_USER` 和 `MEDCOLLECT_DB_PASSWORD` 取值 |
| 持久化 | 原件在 `/zoesoft/med-collect/state/originals`。Oracle 保存相对路径、摘要和业务记录 |
| 全网搜索 | Docker Compose 项目 `med-collect-search`，目录 `/zoesoft/searxng`。采集容器通过 `http://searxng:8080/search` 调用 |
| 启动与日志 | Docker 开机启动，容器策略 `unless-stopped`，日志保留 3 个文件，每个 10 MB |

`releases/` 每个发行保留 JAR、构建文件、摘要与镜像 ID，`images/` 保存经过校验的 Temurin 基础镜像包。现场发行标识与 SHA-256 以 `release.env` 和对应发行目录的 `SHA256SUMS` 为准。

服务器上的部署文档和交接记录为 `/zoesoft/med-collect/deployment.md` 与 `/zoesoft/med-collect/handoff.md`。

SSH 使用运维方登记的主机指纹。管理员和可选的审核员在首次部署时从启动配置导入 Oracle，之后可在「用户管理」维护账号。

首次部署按 README 的变量表准备 `config/app.env`，将 `deploy/compose.yaml` 放在部署目录。用 `10001:10001` 创建 `state/`，再将首个发行所用的镜像标签和发布地址写入 `release.env`。例如 `MEDCOLLECT_IMAGE=med-collect:<发行标识>` 和 `MEDCOLLECT_PUBLISH_HOST=127.0.0.1`，端口可用 `MEDCOLLECT_PUBLISH_PORT` 调整。[变量与构建说明](../README.md#oracle-与内网运行)

## 状态检查与配置

在有 Docker 权限的服务器终端执行。将检查地址替换为 `release.env` 中设置的发布地址。

~~~bash
cd /zoesoft/med-collect
docker compose --env-file release.env ps
docker compose --env-file release.env logs --tail 80 app
base_url=http://127.0.0.1:18085
curl --noproxy '*' -fsS -o /dev/null -w '%{http_code}\n' "$base_url/"
curl --noproxy '*' -fsS -o /dev/null -w '%{http_code}\n' "$base_url/api/csrf"
~~~

预期状态为 `running` 且 `healthy`，两个接口均返回 `200`。登录后复核已同步样本的类别、摘要、业务主键和 PDF 原件。管理员在「系统设置」运行模型测试和 4.23 连接测试。[PDF 验收记录](handoff.md#节点-6--真实-pdf-验收)

环境变量含义见仓库 README 的「Oracle 与内网运行」。在 `config/app.env` 维护应用变量。Compose 以 `KEY=value` 读取，含空格或美元符号的值用单引号。更新配置时保留原 `MEDCOLLECT_SETTINGS_KEY`，以解密 Oracle 中已保存的模型 API 密钥。容器监听地址和端口由 `compose.yaml` 管理。配置修改后重建容器以应用环境变量。

~~~bash
cd /zoesoft/med-collect
docker compose --env-file release.env config --quiet
docker compose --env-file release.env up -d --force-recreate --wait --wait-timeout 60 app
~~~

如启动失败，先检查 Compose 日志中的 Oracle 连接和 Liquibase 信息。4.23 调用失败时核对容器出网、业务账号及证书指纹。全网搜索连接和原文下载均依赖服务器出网。

账号保存在 Oracle 的 `MC_USER_ACCOUNT`。注册账号默认使用审核员角色。管理员可新建、启用、停用、调整角色及重置密码。停用、角色变更和密码重置会让该账号的现有会话失效。初始化完成后，应用从数据库读取账号与权限。

迁移时曾发现容器健康检查通过后，首次登录仍因宿主机熵不足而阻塞。Dockerfile 通过 `-Djava.security.egd=file:/dev/urandom` 指定 JVM 随机种子源。更换基础镜像或启动参数后，从新容器启动开始重新验证首次登录。[OpenJDK 21 随机种子配置](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/conf/security/java.security#L116-L155)

## 知识同步查重与版本记录

在「知识审核管理」打开待审稿，点击「检查重复与差异」，核对目标知识及字段对照。系统在 4.23 的知识类型、子类型及所选末级类别内优先匹配 DOI。缺少可比 DOI 时，使用归一化的稿件标题或原文标题。多个候选由审核人选择业务主键。查重结果和审批绑定到当前稿件修订号。

| 查重结果 | 审核通过后的处理 |
| --- | --- |
| 无匹配 | 同步前再次查重，仍无匹配时新增业务记录 |
| 内容一致 | 登记跳过，关联原业务主键 |
| 内容有变化 | 展示标题、摘要、模板字段、业务关联和 PDF 附件差异。同步前复核业务记录，传入原 ID 更新 |

4.23 的查询接口返回正文，DOI 可能出现在来源网址、模板字段或备注。接口未提供独立 DOI 过滤。采集系统分页核对同类候选，每次最多 30 页，每页 50 项。若范围超限，页面会提示缩小子类。相同字节的 PDF 沿用已有附件。更新后的 PDF 作为新附件绑定，已存在的文件及参考文献条目保留。

已同步稿件可通过「发起新版本」编辑和重新提交审核。「版本记录」按业务主键显示采集系统记录的版本号、审核人、同步时间、前后快照和差异。Oracle 迁移 `006-knowledge-sync.sql` 新增审核快照、版本记录和同步租约。4.23 的发布日志属于独立接口，采集版本号存于 `MC_SYNC_VERSION`，每个业务主键独立编号。

4.23 保存时可能按业务权限重置审核或发布状态，并停用已发布的知识。审核页提示这一影响，版本快照保存 4.23 回读到的实际状态，后续启用和发布按 4.23 业务流程处理。

写入前发现 4.23 记录在审核后变化时，稿件回到待审。重新查重并审核后再同步。响应中断时按原业务记录和同步标识回读，并续做缺失步骤。同一业务记录上有未完成写入时，先查看并恢复先前操作。需要核对历史新增时，在业务系统按标题和 `[MC:稿件ID]` 查找，在采集系统登记结果。升级前已审核但尚未发出新增请求的稿件，需按新版重新核对查重结果并审批。

0.1.4 上线前，Oracle 预演确认 5 项增量迁移。隔离容器从 4.23 只读查询已同步论文 `10.1371/journal.pone.0155677`，同内容及同 PDF 判定跳过，改摘要后命中原 ID 并显示摘要差异。9 项后端回归通过，覆盖审批约束、按 ID 写入与回读、恢复中断写入、PDF 新旧附件和历史快照。生产验收使用只读的知识与原件接口。[版本验收交接](handoff.md#节点-13--同步前查重与知识版本)

## SearXNG 全网搜索

SearXNG 独立部署在 `/zoesoft/searxng`，使用 `med-collect_default` Docker 网络。8080 只在容器网络内使用。仓库的 `deploy/searxng/compose.yaml` 和 `deploy/searxng/settings.yml` 分别对应服务端的 `compose.yaml` 和 `config/settings.yml`。运行用户为 `977:977`，配置目录只读挂载，缓存写在 `cache/`。`runtime.env` 保存生成的 `SEARXNG_SECRET`，权限为 `0600`。[SearXNG 容器安装与配置](https://docs.searxng.org/admin/installation-docker.html)

现网镜像版本为 `2026.11.23-a7163d091`，本地标签为 `med-collect-searxng:c9ea2372a7f1`。官方 Linux amd64 清单记录在 `images/upstream.ref`，镜像 ID 记录在 `images/image.id`。服务器拉取镜像层时遇到证书校验失败，本次在构建机核验官方清单、镜像配置和各层 SHA-256 后导入。恢复现版可使用现有离线包。

~~~bash
cd /zoesoft/searxng
(cd images && sha256sum -c SHA256SUMS && docker load -i searxng-amd64-c9ea2372a7f1.tar)
test "$(docker image inspect --format '{{.Id}}' med-collect-searxng:c9ea2372a7f1)" = "$(cat images/image.id)"
docker network inspect med-collect_default --format '{{.Name}}'
docker compose --env-file release.env config --quiet
docker compose --env-file release.env up -d --wait --wait-timeout 90 searxng
docker compose --env-file release.env ps
~~~

全新恢复时先启动采集项目，建立 `med-collect_default` 网络，再启动 SearXNG。创建 `config/` 和 `cache/`，所有者设为 `977:977`，目录权限设为 `0750`。复制仓库配置，将 `config/settings.yml` 的所有者设为 `977:977`，权限设为 `0440`。设置 `release.env` 中的 `SEARXNG_IMAGE`。缺少 `runtime.env` 时，在部署目录以 root 执行 `(umask 077; printf 'SEARXNG_SECRET=%s\n' "$(openssl rand -hex 32)" > runtime.env)`。

若需要停止并移除两个项目的容器，先对 SearXNG 执行 `down`。单独更新 SearXNG 时，保留 `runtime.env`，将核验后的新镜像标签写入 `release.env`，再启动并执行下面的查询。更新 `config/settings.yml` 后重建 SearXNG 容器以加载配置。

采集端 `/zoesoft/med-collect/config/app.env` 的 `MEDCOLLECT_SEARX_URL` 为 `http://searxng:8080/search`，`MEDCOLLECT_PRIVATE_SOURCE_HOSTS` 包含 `searxng`。初次接入时把该主机名追加至原有允许名单，保存后按「状态检查与配置」重建采集容器。回退接入时清空 `MEDCOLLECT_SEARX_URL`，移除名单中的 `searxng`，再重建采集容器。

~~~bash
docker exec med-collect-app-1 curl --noproxy '*' -fsS http://searxng:8080/healthz
docker exec med-collect-app-1 curl --noproxy '*' -fsSG --max-time 18 http://searxng:8080/search \
    --data-urlencode 'format=json' \
    --data-urlencode 'q=home blood pressure monitoring hypertension PLOS ONE' |
    python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d["results"]), d["unresponsive_engines"])'
~~~

在「知识采集管理」用管理员账号新建手动「全网搜索」任务，勾选「先搜索，再人工选择结果采集」。搜索得到候选后选一条公开全文，检查采集回执关联到该次搜索，原文可下载，并保留在待审状态。`unresponsive_engines` 可用于定位出网失败的上游，候选需要能访问发布页或合法公开的原件地址。[SearXNG Search API](https://docs.searxng.org/dev/search_api.html)

当前启用 Bing、ChinaSo 和 Crossref。验收关键词从 Bing 和 Crossref 返回结果，论文候选来自 Crossref。为保留发布方网址，SearXNG 关闭了默认的 DOI 网址改写。SearXNG 内的 Europe PMC 和 PubMed 因现场上游请求超时暂停启用。采集系统的独立 Europe PMC 检索模式沿用原有配置。

生产验收保存了 20 条候选，选中一篇 PLOS ONE 公开论文并新增 1 份 HTML 全文，采集失败数为 0。下载文件和服务器原件的 SHA-256 一致。[选采验收交接](handoff.md#节点-12--searxng-部署与现网选采)

## 原件存储与备份

`/zoesoft/med-collect/state/originals` 对应容器的 `/var/lib/med-collect/originals`，由 UID/GID `10001:10001` 写入。PDF、HTML、DOCX、TXT、CSV、XLS、XLSX 和文献检索元数据均以文件保存。需要提取文本的资料在同一子目录有 `extracted.txt`。Oracle 的 `MC_ORIGINAL.ORIGINAL_PATH` 为相对路径，`MC_MATERIAL.SOURCE_ABSTRACT` 保存源摘要。可按文件名或资料 ID 查询路径。

~~~sql
SELECT m.id, m.file_name, m.format_code, m.size_bytes, m.sha256, o.original_path
FROM mc_material m JOIN mc_original o ON o.material_id = m.id
ORDER BY m.captured_at DESC;
~~~

连接同一 Oracle schema 的实例须挂载同一份原件目录。备份和恢复时，将同一时间点的 Oracle 数据和原件目录配套保存。恢复后核对 `ORIGINAL_PATH` 对应的文件存在，字节数和 SHA-256 与 `MC_MATERIAL` 一致，并通过应用预览原件。

升级旧数据库时，可先用 `SPRING_LIQUIBASE_CONTEXTS=file-storage-verify` 运行试迁容器，使用正式的原件目录并关闭调度和同步写入。试迁中核对服务器文件和 API 回读。正式切换时停止旧版实例，用默认的 `file-storage-cutover` 上下文重新检查原件并清理原件 BLOB 和提取文本 CLOB。[Liquibase 自定义迁移](https://docs.liquibase.com/reference-guide/customchange)

## 发布新版本

### 1. 构建、校验并上传

在构建机仓库根目录使用 Java 21、Node.js 22.12+ 和 pnpm 11。若 POM 版本变化，调整 `$version`。应用涉及新 Liquibase changeset 时，在上线前核对上一应用版本与迁移后的 Oracle schema 的兼容性。

~~~powershell
pnpm --dir web install --frozen-lockfile
if ($LASTEXITCODE -ne 0) { throw '安装前端依赖失败' }
pnpm --dir web build
if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
mvn -f backend/pom.xml clean verify
if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败' }

$version = '0.1.5'
$artifact = (Resolve-Path "backend/target/med-collect-$version.jar").Path
$sha = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
$release = '{0}-{1}-{2}' -f $version, (Get-Date -Format 'yyyyMMdd-HHmmss'), $sha.Substring(0, 12)
$target = Read-Host '部署 SSH 地址，例如 deploy@medcollect.example.org'
$releaseDir = "/zoesoft/med-collect/releases/$release"

ssh $target "mkdir -m 0750 -- '$releaseDir'"
if ($LASTEXITCODE -ne 0) { throw '创建发行目录失败' }
scp $artifact ("{0}:{1}/med-collect.jar" -f $target, $releaseDir)
if ($LASTEXITCODE -ne 0) { throw '上传 JAR 失败' }
scp deploy/Dockerfile ("{0}:{1}/Dockerfile" -f $target, $releaseDir)
if ($LASTEXITCODE -ne 0) { throw '上传 Dockerfile 失败' }
ssh $target "printf '%s  med-collect.jar\n' '$sha' > '$releaseDir/SHA256SUMS'"
if ($LASTEXITCODE -ne 0) { throw '写入 SHA-256 校验文件失败' }
Write-Output $release
~~~

JAR 应包含 `BOOT-INF/classes/static/index.html` 和 Vue assets。应用镜像构建只读取对应的发行目录。若版本同时更新了仓库 `deploy/compose.yaml`，在服务器备份现用 Compose 文件，审阅差异后安装新版并用 `config --quiet` 校验。

### 2. 构建容器镜像并切换

在服务器 root 终端填入上一步输出的发行标识再执行。每个新版本使用独立镜像标签。启动失败时此命令通过当前 `release.env` 拉起上一版本。切换成功后按「状态检查与配置」验收。

~~~bash
(
    set -euo pipefail
    cd /zoesoft/med-collect
    release='<上一步输出的发行标识>'
    [[ $release =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$ ]]
    release_dir="$PWD/releases/$release"
    image="med-collect:$release"
    exec 9>.deploy.lock
    flock -w 30 9

    base_image=$(cat images/temurin-base.ref)
    if ! docker image inspect "$base_image" >/dev/null 2>&1; then
        (
            cd images
            sha256sum -c SHA256SUMS
            docker load -i eclipse-temurin-21-jre-jammy-04bcffe1d3d3.tar
        )
    fi
    test "$(docker image inspect --format '{{.Id}}' "$base_image")" = "$(cat images/temurin-image.id)"
    (cd "$release_dir" && sha256sum -c SHA256SUMS)
    jar_sha=$(cut -d ' ' -f 1 "$release_dir/SHA256SUMS")
    printf '%s\n' "$base_image" > "$release_dir/base-image.ref"
    cp images/temurin-upstream.ref "$release_dir/base-upstream.ref"
    DOCKER_BUILDKIT=0 docker build --pull=false --tag "$image" \
        --build-arg "TEMURIN_IMAGE=$base_image" --build-arg "JAR_SHA256=$jar_sha" "$release_dir"
    docker image inspect --format '{{.Id}}' "$image" > "$release_dir/image.id"

    next_env=$(mktemp "$PWD/.release.XXXXXX")
    backup_env=$(mktemp "$PWD/.previous.XXXXXX")
    trap 'rm -f -- "$next_env" "$backup_env"' EXIT
    sed "s|^MEDCOLLECT_IMAGE=.*$|MEDCOLLECT_IMAGE=$image|" release.env > "$next_env"
    grep -Fxq "MEDCOLLECT_IMAGE=$image" "$next_env"
    docker compose --env-file "$next_env" config --quiet

    cp -p release.env "$backup_env"
    if docker compose --env-file "$next_env" up -d --wait --wait-timeout 60 app; then
        mv -T "$backup_env" previous.env
        mv -T "$next_env" release.env
    else
        docker compose --env-file "$next_env" logs --tail 80 app || true
        docker compose --env-file release.env up -d --wait --wait-timeout 60 app
        exit 1
    fi
)
~~~

基础镜像取自 Eclipse Temurin 官方 Docker 仓库的 Linux amd64 清单。服务器直拉超时后，从构建机下载并核对清单、镜像配置和各层的 SHA-256，经 SSH 传至服务器。现网沿用 CentOS 7 上已验证的本地 Docker 构建方式。基础镜像更新时按摘要核验新镜像和 Java 版本，并在切换前用新镜像执行应用验收。[Temurin 容器镜像](https://adoptium.net/installation/containers/) · [Compose 启动与健康等待](https://docs.docker.com/reference/cli/docker/compose/up/)

## 0.1.5 工作台验收与回滚

此版本使用现有 Oracle 表、原件卷和 4.23 接口。部署前分别构建 Vue 和 Spring Boot，核对 Vite 输出的资源标识已进入 JAR。使用生产 Oracle 预演时关闭调度及业务同步，使读取的原件目录与该 schema 对应。

发布后登录概览，依次检查任务、原文、解析、审核四张流程卡，并打开最近任务。切换专科后核对同一条件下的概览计数和列表总数。用已有 PDF 稿件验证「对照查看」，切换第 1 页和第 2 页，核对 `/api/materials/{id}/pdf-preview/{page}` 返回 `image/jpeg`。点击「打开 PDF」，核对原件预览响应的 `Content-Type: application/pdf`、`X-Frame-Options: SAMEORIGIN` 及原文件 SHA-256。用已同步稿件复验时只需查看，无需发起新版本或业务写入。

0.1.5 可按本文回滚命令切回 0.1.4。若已经创建结果上限超过 20 条的任务，0.1.4 的编辑表单只能保存至 20 条。首次上线时以服务器 `release.env`、`previous.env` 和该发行的 `SHA256SUMS` 定位可回退镜像。

## 回滚

`previous.env` 保存上一个镜像和发布地址。回滚到 0.1.3 前，待进行中的同步结束，在 `config/app.env` 设 `MEDCOLLECT_CDSS_SYNC_ENABLED=false`，避免旧版将新版修订稿按新增处理。保留 `006` 迁移的数据。重新部署支持版本更新的版本并核对未完成同步后，再启用业务写入。采集计划和原件读取可在回滚期间使用。核对兼容性后可在服务器执行以下命令，再做状态和功能验收。

~~~bash
(
    set -euo pipefail
    cd /zoesoft/med-collect
    exec 9>.deploy.lock
    flock -w 30 9
    test -f previous.env
    docker compose --env-file previous.env up -d --wait --wait-timeout 60 app
    cp -p previous.env release.env
)
~~~

应用回滚复用现有 Oracle 数据。涉及数据库兼容性的版本按对应迁移方案处理。
