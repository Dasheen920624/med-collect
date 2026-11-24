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

现网运行 `med-collect:0.1.14-20261124-9a8acc35ecaf`，JAR SHA-256 为 `9a8acc35ecaf049682114408a2ab6a6ee9284e636abccd1ac5cb19085d84d7c0`。现网和上一发行标识分别记录在 `release.env` 与 `previous.env`。[3.27 医疗文献采集工作台](http://192.168.3.27:18085/)

## 0.1.14 原件移除与相似资料审核

- Liquibase 012 在 `MC_MATERIAL` 增加 `ARCHIVED_AT`、`ARCHIVED_BY`、`ARCHIVE_REASON`。管理员从原文详情移除已有审批或同步历史的资料时，应用调用 `POST /api/materials/{id}/archive`。原文、解析、审核列表与概览统计排除已移除资料，历史详情及同步回执作为只读记录保存。无保留依赖的原件仍走永久移除接口。
- 业务近似查询在同一类型和子类范围使用标题片段找候选，再按规范化标题比较。匹配相似资料时，审核人可明确确认新增，或填写原因选择「不予同步」。已保存的旧查重单可点击「重新查重」补查。同步准备写入前复核业务匹配，变化时回到待审。停用位置提示按 4.23 业务目录显示，停用由具备权限的审核人员在 4.23 办理。
- 原件预览、下载和新绑定的业务附件统一采用可读文件名。优先取原文中文题名或已有中文知识标题，沿用实际文件格式后缀。原件卷路径及实际文件指纹用于恢复核验。
- 发布验收应核对移除记录从活动列表消失且历史回执仍可读，核实 PDF、HTML 的文件链接和下载名，检查旧查重补查和不予同步状态。已有归档或不予同步记录的 Oracle schema 须使用支持这两项状态的 0.1.14 或后续发行恢复。

## 0.1.12 至 0.1.13 升级验收

1. 使用原有 Oracle、文件卷、会话及业务账号配置部署新镜像。0.1.12 的迁移 010 增加发布状态回读、解析执行记录与任务资料类型。0.1.13 的迁移 011 为 `MC_PARSE_ATTEMPT` 增加可空的 `TYPE_CODE` 和 `CLASS_CODE`。旧原文和已生成知识可直接打开，上一发行可以读取升级后的数据库。
2. 从「静态知识解析」选择一份已完成的 PDF，点击「查看解析结果」，打开其中一条知识。离开菜单、刷新页面并重新打开，核对知识 ID、更新时间、结果数量相同。列表和查看只发 GET 请求。页面上明确选择解析时，`POST /api/materials/{id}/drafts` 携带 `reuseExisting: true`。同原文、类型和子类返回已有稿件，同时发起的同模板解析只允许一个进入模型处理。
3. 在桌面、平板和手机宽度核对八个菜单、概览的四个入口及「知识采集管理」的创建者。滚动来源和分类表到最右，操作与使用数应可完整阅读。审核前检查正文、知识出处、采集来源、目标类别和差异。已同步原稿的修订进入人工待审，按原业务 ID 更新仍须审核。
4. 现场核验和分批处理记录在 `/zoesoft/med-collect/operations/manuals-mainland-20261124/prototype-0.1.12/`。其中 `before.json` 保存变更前快照，`draft-checks/` 按原稿 ID 保存核验结果，`after.json` 用于对比。回退应用时保留 Oracle 迁移、原件及已有审核记录。

## 说明书来源与资料移除

业务新增来源迁移为 `009-sync-origin.xml`。说明书来源与原件移除沿用 `008-material-removal.sql`。

1. `config/app.env` 中的 `MEDCOLLECT_MANUAL_SOURCE_HOSTS` 配置已核实的发布机构主机，以逗号分隔。现场值为 `jyk.eraweb.cn,www.adicon.com.cn,www.greatmaster.cn,www.gdghospital.org.cn,chinanm.cma.org.cn`。新增来源时先核对机构所在地、原始编写者、发布页和附件。将机构登记在批次清单中，并把实际使用的发布及附件主机加入配置后重建容器。应用按主机完整名称核验来源及重定向地址，在检验和检查说明书建稿、提交、查重、审批和发起同步时生效。
2. 管理员在「文献原文管理」打开原件详情，选择「移除这份资料」，填写原因并确认。已审批、发生过同步、已有业务版本或仍被其他待审稿引用的原件受保护。已移除的原件不可再次按相同指纹采入。删除时同步处理本地草稿、分类和任务结果关联，执行历史保留移除标志。
3. Oracle 的 `MC_REMOVED_MATERIAL` 保存移除指纹及文件清理状态。文件在数据库提交后删除。若磁盘暂时不可用，后台每分钟重试。查询 `SELECT COUNT(*) FROM mc_removed_material WHERE pending_path IS NOT NULL` 可检查积压。本批已确认积压为 0。历史回执位于 `operations/manuals-mainland-20261124/`，该目录权限为 `0750`。
4. 在该批尚处于人工待审状态时，可在上述目录运行 `python3 readback.py`，输入采集工作台账号，通过正式 API 和原件卷复验摘要、PDF 页数、类型、字典及待审状态。审核员开始办理后，可按清单中的稿件 ID 查看新状态及同步版本记录。旧采集包已清除移出范围的原件，旧审核首页指向当前清单。

## 大批量网址采集

批量网址采集从 0.1.8 起支持分批提交和回执续跑，现网发行标识见上节。

将公开原件网址逐行写进 UTF-8 文本。在部署目录运行下面的命令。工具使用 Python 3 标准库，交互式读取采集账号和密码。更换来源列表或批大小时使用新的回执文件。

~~~bash
cd /zoesoft/med-collect
python3 collect_urls.py sources.txt --name 中文说明书 --plan
python3 collect_urls.py sources.txt --name 中文说明书 --base http://192.168.3.27:18085 --report collection-receipt.json
~~~

同一来源列表内自动去重，每个任务最多 100 个网址。可用 `--batch-size` 调小每批数量。中断后重跑第二条命令会恢复已有任务和执行记录。回执列有每个网址的执行状态及原件 ID，`missingOriginals` 给出仍需补采的地址。可把这些地址写入另一份列表再次采集。工具只办理原件采集。

来源短暂超时或返回 408、429、502、503、504 时，采集端最多重试一次。服务端要求等待超过两秒的请求保留失败回执，可稍后重跑。模型分析 PDF 时始终请求中文摘要；扩展字段为空时仍会提取摘要。模型输出了无效 JSON 或空摘要时，会携带同一段原文再请求一次。连续失败可从原件详情重新解析。

正式批次的文件位于 `/zoesoft/med-collect/operations/manuals-net400-20261124/`。将 `review.html` 下载到可访问内网的工作机打开，即可按项目、字典编码和来源筛选，进入真实待审稿。原件由 `/zoesoft/med-collect/state/originals/` 持久化。交接包中的 `audited-creates.json`、`oracle-readback.json` 和 `native-readback.json` 分别保存逐份复核、Oracle 核验和业务字典回读结果。

在 3.27 的该批次目录运行 `python3 readback.py` 可重新核验审批前快照。已有稿件进入人工审核或同步阶段时，应以稿件详情的最新状态、业务主键和版本回执核对。[说明书验收](collection-audit.md#2026-11-24-拟新增说明书逐份复验)

## 首次安装与账号

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

在「知识审核管理」打开待审稿，点击「核对重复与相似资料」，核对目标知识及字段对照。指南、书籍、期刊在所选类型和子类内以 DOI 或规范化标题匹配。疾病、检验等结构化知识按业务知识标题匹配，同篇论文可为不同知识提供依据。多个候选由审核人选择。名称有差异时可以查询同类型和子类的业务知识并指定目标，人工指定保留业务标题并显示字段差异。查重结果和审批绑定到当前稿件修订号。

| 查重结果 | 审核通过后的处理 |
| --- | --- |
| 无匹配 | 同步前再次查重，仍无匹配时新增业务记录 |
| 内容一致 | 登记跳过，关联原业务主键 |
| 内容有变化，原 ID 有采集系统新增回执 | 展示字段、字典和 PDF 差异，人工审批后复核业务记录，沿用原 ID 更新并登记版本 |
| 内容有变化，原 ID 无采集系统新增回执 | 归入「业务原有 · 仅对照」，保留原件、业务链接和差异供查阅 |

4.23 的查询接口返回正文，DOI 可能出现在来源网址、模板字段或备注。接口未提供独立 DOI 过滤。采集系统分页核对同类候选，每次最多 30 页，每页 50 项。若范围超限，页面会提示缩小子类。相同字节的 PDF 沿用已有附件。更新后的 PDF 作为新附件绑定，已存在的文件及参考文献条目保留。

由采集系统首建的业务知识可通过「发起新版本」编辑和重新提交审核。「版本记录」按业务主键显示采集系统记录的版本号、审核人、同步时间、前后快照和差异。Oracle 迁移 `006-knowledge-sync.sql` 新增审核快照、版本记录和同步租约。采集版本号存于 `MC_SYNC_VERSION`，每个业务主键独立编号。

0.1.10 使用 `MC_SYNC_ORIGIN` 登记业务 ID、首建稿件 ID 和时间。新建请求回读核对后，来源登记和取得业务主键在同一事务提交。历史回填只接受与稿件业务 ID 一致的 `SYNC_CREATED` 事件或成功的 `CREATE` 版本。导入的业务资料、手选目标、DOI、标题和字典用于查重。匹配或登记跳过均不产生新增回执。审批、同步、附件续传和发起新版本均执行来源校验。

升级时停用旧应用实例，待进行中的同步结束后运行 Liquibase。迁移会撤回来源未经证实的待执行更新，保留旧差异和已发生的同步步骤，标记为 `REFERENCE_ONLY`，并清除该计划原有的审批凭据。已经完成的同步保留原有历史。可用以下查询核对回填来源与队列。

~~~sql
SELECT remote_id, knowledge_id, created_at FROM mc_sync_origin ORDER BY created_at;
SELECT status, COUNT(*) FROM mc_knowledge GROUP BY status ORDER BY status;
~~~

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

在「知识采集管理」用管理员账号打开「全网搜索」，选择先搜索、再选择结果采集。搜索得到候选后选一条公开全文，检查采集回执关联到该次搜索，原文可下载，并保留在待审状态。`unresponsive_engines` 可用于定位出网失败的上游，候选需要能访问发布页或合法公开的原件地址。[SearXNG Search API](https://docs.searxng.org/dev/search_api.html)

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

$version = '0.1.14'
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

在服务器 root 终端填入上一步输出的发行标识再执行。每个新版本使用独立镜像标签。切换成功后按「状态检查与配置」验收。启动失败保留日志和旧发行指针，按迁移兼容性选择恢复镜像。

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
        exit 1
    fi
)
~~~

基础镜像取自 Eclipse Temurin 官方 Docker 仓库的 Linux amd64 清单。服务器直拉超时后，从构建机下载并核对清单、镜像配置和各层的 SHA-256，经 SSH 传至服务器。现网沿用 CentOS 7 上已验证的本地 Docker 构建方式。基础镜像更新时按摘要核验新镜像和 Java 版本，并在切换前用新镜像执行应用验收。[Temurin 容器镜像](https://adoptium.net/installation/containers/) · [Compose 启动与健康等待](https://docs.docker.com/reference/cli/docker/compose/up/)

## 0.1.7 写入 4.23 与回读

从正式页面按「文献原文管理 → 静态知识解析 → 知识审核管理」验收一次外部文献。审核人核对业务目标、自动匹配或指定的主键、摘要、关联字典、附件和差异，再完成人工审批。点击「同步至 4.23」后从「查看写入记录」打开业务知识。回执中的「核验 4.23 当前内容」会重新读取业务详情、有效附件和 PDF 字节，逐项显示类别、标题、摘要和原件的比对结果。已处理的旧版本也可回读，若业务端之后有新的修订，结果会反映当下差异。

- 新增使用 `/knowledge/save` 返回的 ID。变更使用同一保存接口并携带已审核的业务 ID。更新和附件引用补存均发送业务子类型、已有类别和应保留的字典关系。按 4.23 保存契约，省略关联参数可能清除原有关系。
- 业务端知识附件以目录和上传名确定物理路径。新 PDF 的上传名由稿件 ID 与文件 SHA-256 组成，界面显示的附件名保留原名。绑定前下载校验，完成前核对业务正文与原件。4.23 无法完整列出附件、文件无法读取或回读不符时，先核对异常回执。
- 业务端可能因正文更新触发自己的停用、重审或发布流程。查重与版本记录以实际业务主键为索引。历史上早于版本功能的新增由当时的执行事件识别，显示该笔新增的业务回执。
- 此次升级复用 0.1.6 的 Oracle schema 和原件卷。可先将同一镜像接入独立 H2，关闭调度与业务写入，用真实出版社 PDF、模型和 4.23 读接口验证拟新增和拟更新。正式验收应使用已有获批记录回读。回退 0.1.6 后先暂停业务写入，该版本处理字典和同名 PDF 的行为见核查记录。[出站同步核查](collection-audit.md#017-从文献到业务知识)

## 0.1.6 业务资料与文献选采

在「业务分类」点击「同步业务资料」。该操作只读访问业务端，向采集 Oracle 写入目录、提取模板和参数字典快照。启用采集调度时，应用启动后检查快照，此后每六小时检查更新。上游接口失败时保留上一次完整快照，页面显示同步时间及错误。更新在同一数据库事务内完成，按业务编码复用原分类 ID，保留本地分类和历史引用。已从业务系统移除的分类停用。已经人工通过或已同步的知识能按确定的业务类别补全原文归属，已有人工分类优先。

接手时先核对健康状态，再从「业务分类」检查来源标记和同步时间。使用采集账号查询 `GET /api/business-catalog`，核对当前授权类型、专科、病种、模板和参数字典数。「解析模板」可查看同步的字段和业务子类。ICD-10、药品、检验、检查等大字典按需搜索，保留 4.23 保存接口要求的 ID 或编码。新建和更新业务知识前再次核验已选值。业务账号权限变更后，管理员可手动同步。

在「知识采集管理 → 从 4.23 导入文献」选择期刊、指南或书籍，按标题或子类查询，打开任意结果查看来源、当前摘要和附件。选择一份原件或「仅采集来源和摘要」，点击「采集到原文库」产生执行回执。此入口取得的原件沿用同一服务器文件卷，新的资料进入原文核验队列。再次取得相同版本会返回重复回执，审核通过后才可回写知识。两个方向使用独立入口及会话权限。

Liquibase 的 `007-business-catalog.xml` 包含分类来源列、唯一索引和快照设置。正式切换时可先用同镜像的临时容器连接 Oracle，关闭采集调度和 4.23 写入，运行目录同步并核对既有资料。临时容器回读正式原件时，可将已存在的原件分片只读挂载在可写的临时根目录下。采集原件的演练使用单独数据库和可写原件卷。

回退到 0.1.5 前检查 `SELECT COUNT(*) FROM mc_task WHERE collection_mode='BUSINESS'`。0.1.5 无法识别业务文献来源任务。如果数量大于零，保留本版数据库并使用支持 BUSINESS 的修复镜像，或配合已验证的上线前数据库及文件备份整体恢复。旧版运行期间暂停分类维护。恢复新版后核对目录快照、任务回执和未完成的知识审核。

## 0.1.5 工作台验收与回滚

此版本使用现有 Oracle 表、原件卷和 4.23 接口。部署前分别构建 Vue 和 Spring Boot，核对 Vite 输出的资源标识已进入 JAR。使用生产 Oracle 预演时关闭调度及业务同步，使读取的原件目录与该 schema 对应。

发布后登录概览，依次检查任务、原文、解析、审核四张流程卡，并打开最近任务。切换专科后核对同一条件下的概览计数和列表总数。用已有 PDF 稿件验证「对照查看」，切换第 1 页和第 2 页，核对 `/api/materials/{id}/pdf-preview/{page}` 返回 `image/jpeg`。点击「打开 PDF」，核对原件预览响应的 `Content-Type: application/pdf`、`X-Frame-Options: SAMEORIGIN` 及原文件 SHA-256。用已同步稿件复验时只需查看，无需发起新版本或业务写入。

0.1.5 可按本文回滚命令切回 0.1.4。若已经创建结果上限超过 20 条的任务，0.1.4 的编辑表单只能保存至 20 条。首次上线时以服务器 `release.env`、`previous.env` 和该发行的 `SHA256SUMS` 定位可回退镜像。

## 回滚

`009` 已执行的数据库包含 `REFERENCE_ONLY` 及新增来源登记。使用支持该状态和来源保护的镜像前滚修复。0.1.9 及更早版本会把业务原有记录列为更新目标，且无法读取新状态。需要整体恢复旧数据库时，配套恢复对应时间点的原件，先关闭旧版对 4.23 的写入，恢复来源保护并验收后再开放写入。以下镜像切换命令仅用于已核对兼容性的版本组合。

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

## 中国大陆说明书批次复验

Oracle 用户为 `ZYMEDCOLLECT`，原文元数据表 `MC_MATERIAL` 与新增来源表 `MC_SYNC_ORIGIN` 位于 `ZYMEDCOLLECT_TAB` 表空间。原件表 `MC_ORIGINAL.ORIGINAL_PATH` 存相对路径，宿主机根目录为 `/zoesoft/med-collect/state/originals/`。本批原件的文件路径为 `<原件ID前两位>/<原件ID>/original.html` 或 `original.pdf`。迁移或备份时同时保存 Oracle、原件目录和本批审核交接目录。

当前清单位于 `/zoesoft/med-collect/operations/manuals-mainland-20261124/`。复制 `review.html` 到内网审核终端即可打开独立清单，按「拟新增」或「业务原有 · 仅对照」筛选。打开稿件可查看原件和最新查重结果。源机构清单见 `publishers.json`，已核验的两组数据分别保存在 `audited-creates.json` 和 `protected-matches.json`。

在医学审批开始前，可在 3.27 执行以下只读复验。脚本提示输入现网采集账号和密码，检查 Oracle 应用返回的摘要和状态、官方文件指纹、服务器路径、业务项目和 PDF 预览。开始审批后，原报告用于对照当时状态，已批准或已同步的条目在工作台查看当前回执和版本。

~~~bash
cd /zoesoft/med-collect/operations/manuals-mainland-20261124
python3 readback.py
python3 make_review.py
~~~

本批 413 份拟新增稿从「知识审核管理」办理医学审核。183 份只读稿可核对业务原文与差异。现场的 `oracle-readback.json` 保存了本批原件、稿件状态、审批和业务来源核验结果，`protected-business-before.json`、`protected-business-after.json` 保存了 183 个业务 ID 的升级前后指纹。已获批的采集系统首建知识可从详情发起修订，完成后查看业务回执。[批次交接](handoff.md)
