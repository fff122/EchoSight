> ## Documentation Index
> Fetch the complete documentation index at: https://docs.senseaudio.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 接口模型列表

> 查询当前 API Key 所属用户可调用的模型

```http theme={null}
/v1/models
```

## 说明

模型接口用于查询当前 API Key 所属用户可调用的模型。

* **接入域名**：`https://api.senseaudio.cn`
* **模型筛选**：列表接口可通过 `mode` 按模型类型筛选；筛选值会去除首尾空格并转为小写

### 授权

请求必须携带以下任一凭证：

```http theme={null}
Authorization: Bearer <API_KEY>
```

### 查询参数

| 参数     | 类型     | 必填 | 说明                                    |
| ------ | ------ | -- | ------------------------------------- |
| `mode` | string | 否  | 模型模式筛选。为空时返回全部可用模型；服务端按不区分大小写的精确匹配筛选。 |

### 支持的 mode

当前模型平台导出的模型模式及含义如下：

| mode                    | 含义      |
| ----------------------- | ------- |
| `tts`                   | 语音合成    |
| `stt`                   | 语音识别    |
| `llm`                   | 文本生成    |
| `image`                 | 图片生成    |
| `video`                 | 视频生成    |
| `voice_isolation`       | 人声分离    |
| `sound_effect`          | 音效生成    |
| `music`                 | 音乐生成    |
| `realtime_voice_dialog` | 端到端语音模型 |
| `audio_generation`      | 音频生成    |

传入上述列表之外的值时，请求仍会正常处理，但由于没有匹配的模型，通常返回空列表；最终可用值以模型平台实际配置为准。

### 请求示例

```bash theme={null}
curl https://api.senseaudio.cn/v1/models \
  -H "Authorization: Bearer <API_KEY>"
```

按模式筛选：

```bash theme={null}
curl "https://api.senseaudio.cn/v1/models?mode=TTS" \
  -H "Authorization: Bearer <API_KEY>"
```

### 响应示例

```json theme={null}
{
  "object": "list",
  "data": [
    {
      "id": "sensenova-tts-2.0",
      "display_name": "SenseNova TTS 2.0",
      "object": "model",
      "type": "model",
      "mode": "tts",
      "protocols": ["http", "websocket"],
      "created": 1757654400,
      "owned_by": "senseaudio",
      "desc": "高自然度语音合成模型"
    }
  ],
  "first_id": "sensenova-tts-2.0",
  "last_id": "sensenova-tts-2.0",
  "has_more": false
}
```

### 响应字段

| 字段         | 类型           | 说明                      |
| ---------- | ------------ | ----------------------- |
| `object`   | string       | 固定为 `list`              |
| `data`     | ModelItem\[] | 当前用户可调用的模型，按优先级降序排列     |
| `first_id` | string       | 列表第一条模型的 ID；列表为空时为空字符串  |
| `last_id`  | string       | 列表最后一条模型的 ID；列表为空时为空字符串 |
| `has_more` | boolean      | 固定为 `false`             |

### ModelItem

| 字段             | 类型             | 说明              |
| -------------- | -------------- | --------------- |
| `id`           | string         | 模型 ID           |
| `display_name` | string         | 模型名称            |
| `object`       | string         | 固定为 `model`     |
| `type`         | string         | 固定为 `model`     |
| `mode`         | string         | 模型类型            |
| `protocols`    | string\[]      | 模型支持的接口协议       |
| `created`      | integer(int64) | 模型创建时间，Unix 时间戳 |
| `owned_by`     | string         | 模型供应商           |
| `desc`         | string         | 模型描述            |

### 响应与错误

成功响应的 HTTP 状态码为 `200`，响应体直接返回 JSON。

常见错误状态码如下：

| HTTP 状态码 | 含义                         |
| -------- | -------------------------- |
| `401`    | 未提供凭证、凭证无效、API Key 不存在或已停用 |
| `403`    | 访问令牌没有接口权限或用户已禁用           |
| `429`    | API Key 配额不足               |
| `500`    | 模型平台或内部服务异常                |


## OpenAPI

````yaml GET /v1/models
openapi: 3.1.0
info:
  title: SenseAudio Open Platform API
  description: >-
    SenseAudio 开放平台
    API，覆盖语音合成、音频生成、语音识别、音色能力、音乐生成、图片生成、视频生成、智能体与大语言模型等能力。未显式说明的字段为依据素材文档推断。
  version: 1.0.0
servers:
  - url: https://api.senseaudio.cn
    description: 生产环境
security:
  - bearerAuth: []
paths:
  /v1/models:
    get:
      tags:
        - Models
      summary: 查询可用模型列表
      parameters:
        - name: mode
          in: query
          required: false
          description: 模型模式筛选。为空时返回全部可用模型；服务端按不区分大小写的精确匹配筛选。
          schema:
            type: string
      responses:
        '200':
          description: 成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ModelsListResponse'
components:
  schemas:
    ModelsListResponse:
      type: object
      properties:
        object:
          type: string
          description: 固定为 `list`
        data:
          type: array
          items:
            $ref: '#/components/schemas/ModelItem'
          description: 当前用户可调用的模型，按优先级降序排列
        first_id:
          type: string
          description: 列表第一条模型的 ID；列表为空时为空字符串
        last_id:
          type: string
          description: 列表最后一条模型的 ID；列表为空时为空字符串
        has_more:
          type: boolean
          description: 固定为 `false`
      example:
        object: list
        data:
          - id: sensenova-tts-2.0
            display_name: SenseNova TTS 2.0
            object: model
            type: model
            mode: tts
            protocols:
              - http
              - websocket
            created: 1757654400
            owned_by: senseaudio
            desc: 高自然度语音合成模型
        first_id: sensenova-tts-2.0
        last_id: sensenova-tts-2.0
        has_more: false
    ModelItem:
      type: object
      properties:
        id:
          type: string
          description: 模型 ID
        display_name:
          type: string
          description: 模型名称
        object:
          type: string
          description: 固定为 `model`
        type:
          type: string
          description: 固定为 `model`
        mode:
          type: string
          description: 模型类型，例如 `tts`、`llm`、`image`
        protocols:
          type: array
          items:
            type: string
          description: 模型支持的接口协议
        created:
          type: integer
          format: int64
          description: 模型创建时间，Unix 时间戳
        owned_by:
          type: string
          description: 模型供应商
        desc:
          type: string
          description: 模型描述
      example:
        id: sensenova-tts-2.0
        display_name: SenseNova TTS 2.0
        object: model
        type: model
        mode: tts
        protocols:
          - http
          - websocket
        created: 1757654400
        owned_by: senseaudio
        desc: 高自然度语音合成模型
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: API_KEY
      description: 格式：`Bearer <API_KEY>`

````
