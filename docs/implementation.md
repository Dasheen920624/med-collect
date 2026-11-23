# 分析与开发计划

分析日期 2026-11-23。业务系统源码与线上读接口用于核对数据契约。部署使用 Java 21、Spring Boot、Vue 3 和采集专用 Oracle schema。

## 原型范围

已核对原型入口及其 70 个脚本索引。自动采集流程主要在 `collection-journey.js`、`collection-product-workspace.js`、`task-workspace.js`、`collection-redesign.js`、`production-workspace.js`、`knowledge-center-workflow.js`、`sources.js` 和 `collection-conversation-planner.js`。这些模块覆盖采集来源维护、指定网址、栏目检索、关键词检索、手工文件、执行计划、任务记录、原文、原文审核、模板解析、知识审核、同步及同步回执。对话规划在原型使用前端规则生成待确认的采集项，交付时沿用先确认任务再执行的流程。专科门户、评估、规则生产等模块用于理解上下游，本项目的入口是知识自动采集。

## 4.23 实际数据契约

已用所给账号验证 `/api/login`、`/api/knowledge/get-statistics-by-status`、`/api/parameter/find-by-name`、`/api/knowledge/get-class-tree`、`/api/knowledge/query` 和 `/api/knowledge/query-affix`。模板参数名 `knowledge-content-template`。以 `K_KNOWLEDGE.TYPE` 区分业务知识类型，`KNOWLEDGE_CLASS` 保存类别**编码**。部分老数据沿用旧字段名、自由编辑的目录或者空正文，当前新增按业务模板保存 `properties`、`catalog`、`content`。`KR_KNOWLEDGE` 等关系由业务保存服务生成。附件对应 `KR_KNOWLEDGE_AFFIX`，业务服务同时更新 JSON 的 `content.references`。

| 类型及子类型 | 业务含义 | 模板和存储要点 | 业务字典入参 |
| --- | --- | --- | --- |
| 1 | 疾病详情 | `summary`、`pathogeny`、`diagnostic`、`treatment` 等分段 | `icd10Ids` |
| 6 | 药品说明书 | 当前 `mainIngredients`、`usageDosage` 等，验证样本也有旧中文键。业务服务维护药品扩展表 | `drugCodes` |
| 24 / 2401–2404 | 中药知识 | 类别分方剂、药膳食疗、针灸穴位、中药材，各自模板；存量有 `p1`、`c1` 一类旧字段 | `icd10Ids` |
| 8 / 8-1 | 检验知识 | `engName`、`referenceScope`、`clinicalMean`、`indications` 等，子类型必传 | `itemIds` |
| 8 / 8-2 | 检查知识 | `examPart`、`examMethod`、`clinicalMean` 等，子类型必传 | `itemIds` |
| 9 | 症状知识 | `summary`、`pathogeny`、`diagnostic`、`treatment` | `diseaseIds` |
| 10 | 临床路径知识 | 当前模板 `guideEvidence`、`guideReference`、`guideAuthor`，旧样本有 `firstIndex`、`secondIndex` | `icd10Ids`、`deptCode`、`countyUseId` |
| 17 / 30 / 31 | 指南 / 书籍 / 期刊 | 同一个指南编辑入口和模板 17，类别树根 17；`guideType` 属性与目标 `type` 对齐 | 17 支持 `icd10Ids`，30/31 走原生保存分支 |
| 11 | 手术知识 | `summary`、`operativeSteps`、`contraindication`、`afterOpeCare` 等 | `operationIds` |
| 3 | 治疗知识 | `objective`、`operatingProcedures`、`mattersNeedAttention` 等 | `treatmentIds` |
| 13 | 麻醉知识 | `basicInformation`、`summary`、`anesthesiaMethod` 等 | 无 |
| 14 | 输血知识 | `summary`、`references`、`laiyuan` | 无 |
| 15 | 护理知识 | `jieda`、`laiyuan`、`references`，标本采集模板 `15_11` | `diseaseIds`、`nurseIds`、`operationIds`、`itemIds` |
| 18 | 核心制度知识 | `summary`、`mainBody`，作者和发布时间属性 | 无 |
| 2/4/5/7/12/16 | 患者指导、用药建议、检查建议、出院指导、急救、药物护理 | 旧类型，模板 2/4/7 空，16 缺省且无样本。保留已知类型，以补充模板承载缺省目录 | 按 `KnowledgeServiceImpl.save` 的类型分支 |
| 1001–1006 | 诊断依据、治疗方案、检查建议、用药建议、健康教育、出院指导 | `details`、`laiyuan`；部分旧数据自定义目录 | `icd10Ids` |
| 32/38 | 病例、规则来源 | 参数中有独立目录。`38` 对应 `sourceTitle`、`sourceExplain` | 38 支持 `ruleIds` |

术语入口 99 走 `TermController` 及其术语分类，`/knowledge/query` 无此类样本。它是结构化字典，采集文献可作为一般知识或来源保留。`/knowledge/save` 新增后由业务系统保留未启用状态。采集审核通过后手工发起同步，业务侧继续使用其原有审核和发布流程。

源码核对点包括 `KnowledgeController`、`KnowledgeServiceImpl`、`KKnowledge`、`KKnowledgeMapper.xml`、`UploadController`、`KnowledgeContentEdit.vue`、`knowledgeAffixUpload.vue` 和各 `knowledge-dict` 列表。表字段注释不足以判断实际字段，以已验证接口和在用模板为准。

## 数据库与模型

Oracle 12.2.0.1.0 已验证连接。应用迁移以采集专用账户创建 `MC_` 表，继承账户默认表空间。OpenAI 兼容网关 `/v1/models` 已验证包含 `deepseek-flash`。配置通过服务端持久化，密钥通过环境变量提供，界面可更新模型参数。

## 开发节点

1. 建立 Maven/Vue 项目、数据库迁移、用户登录和审计。
2. 实现本地上传、网址和栏目采集、文献及网络检索、任务调度、执行明细和原文库。
3. 实现模板字段、原文审核、可配置模型解析、知识编辑及人工复核。
4. 实现包含回执及断点状态的 4.23 同步。PDF 原件走上传和附件绑定，正文仅使用摘要和出处等元数据。
5. 跑通构建、业务状态流转、Oracle 和内网只读联调。按节点更新 `docs/handoff.md`。

文献检索依据 [Europe PMC REST API](https://europepmc.org/RestfulWebService)。全网检索支持 [SearXNG JSON API](https://docs.searxng.org/dev/search_api.html)。自动从搜索结果使用标为开放获取的 PDF 地址。对只返回元数据或 PDF 不可下载的文献，保留可核验来源和摘要。
