> ## Documentation Index
> Fetch the complete documentation index at: https://docs.senseaudio.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 语音识别转写

> 音频文件转写，支持多种 ASR 模型

export const AsrTranscriptionsErrorHeading = () => {
  useEffect(() => {
    const section = document.createElement("section");
    section.className = "api-section prose prose-gray dark:prose-invert mt-8";
    const heading = document.createElement("h4");
    heading.id = "asr-transcriptions-error-codes";
    heading.className = "api-section-heading-title border-b pb-2.5 border-gray-100 dark:border-gray-800";
    heading.textContent = "错误详情";
    section.appendChild(heading);
    const table = document.createElement("table");
    table.className = "m-0 min-w-full w-full max-w-none table [&_td]:min-w-[150px] [&_th]:text-left [&_td[data-numeric]]:tabular-nums";
    table.innerHTML = `
      <thead><tr><th>status_msg</th><th>业务含义</th></tr></thead>
      <tbody>
        <tr><td>parse file failed: request Content-Type isn't multipart/form-data</td><td>请求不是 multipart/form-data。</td></tr>
        <tr><td>empty audio file</td><td>上传的音频文件为空。</td></tr>
        <tr><td>internal error, please try later again</td><td>下游模型或文件格式错误时返回内部错误。</td></tr>
        <tr><td>invalid authorization</td><td>未提供有效鉴权信息。</td></tr>
        <tr><td>incorrect API key provided</td><td>API Key 无效。</td></tr>
      </tbody>`;
    section.appendChild(table);
    const placeHeading = () => {
      const responseHeading = Array.from(document.querySelectorAll(".api-section-heading-title")).find(node => (/^(响应|Response)$/).test(node.textContent.trim()));
      const responseSection = responseHeading?.closest(".api-section");
      if (responseSection && responseSection.nextElementSibling !== section) {
        responseSection.after(section);
      } else if (!responseSection && !section.isConnected) {
        const content = document.querySelector("main") || document.body;
        content.appendChild(section);
      }
    };
    const observer = new MutationObserver(placeHeading);
    observer.observe(document.body, {
      childList: true,
      subtree: true
    });
    placeHeading();
    return () => {
      observer.disconnect();
      section.remove();
    };
  }, []);
  return null;
};

<AsrTranscriptionsErrorHeading />

## 说明

基于 HTTP 协议的语音识别服务，兼容 OpenAI Audio API 风格，便于从现有系统迁移。

* **接口地址**：`https://api.senseaudio.cn/v1/audio/transcriptions`
* **Content-Type**：`multipart/form-data`
* **鉴权方式**：Bearer Token，详见 [快速接入](/guides/account/quick-access)
* **模型矩阵**：Lite / Standard / Pro / DeepThink，能力差异详见 [语音识别介绍](/guides/asr/overview#模型对比)
* **音频输入**：`file`（≤ 10 MB 直传）或 `file_id`（大文件，先 [文件上传](/api-reference/endpoint/files/upload)）；详见下方表格，两者都传时优先 `file_id`
* **实时识别**：低延迟场景优先使用 [流式 ASR](/api-reference/endpoint/asr/realtime)；二进制帧协议见 [语音识别 WebSocket](/api-reference/endpoint/asr/websocket)

## Authorizations

<ParamField header="Authorization" type="string" required>
  Bearer 鉴权头，格式为 `Bearer SENSEAUDIO_API_KEY`，其中 `SENSEAUDIO_API_KEY` 为您的 API Key。
</ParamField>

## Body

<Note>`multipart/form-data`</Note>

### 音频输入方式

`file` 与 `file_id` **二选一必填**；同时传入时 **优先使用 `file_id`**。

| 参数        | 类型     | 适用场景      | 限制 / 说明                                                                                                                   |
| :-------- | :----- | :-------- | :------------------------------------------------------------------------------------------------------------------------ |
| `file`    | file   | 小文件直接上传转写 | 格式：wav / mp3 / ogg / flac / aac / m4a / mp4 等；大小：≤ 10 MB                                                                  |
| `file_id` | string | 大文件转写     | 先调用 [文件上传](/api-reference/endpoint/files/upload)（`purpose=speech_to_text`，大小 \< 500 MB）获取 `file.file_id`，再传本参数；可替代 `file` |

<ParamField body="file" type="file">
  见上方「音频输入方式」表格。
</ParamField>

<ParamField body="file_id" type="string">
  见上方「音频输入方式」表格。
</ParamField>

<ParamField body="model" type="string" required>
  模型名称。可选值：`senseaudio-asr-lite-1.5-260319`、`senseaudio-asr-1.5-260319`、`senseaudio-asr-pro-1.5-260319`、`senseaudio-asr-deepthink-1.5-260319`。
</ParamField>

<ParamField body="language" type="string">
  音频内容语言代码（ISO-639-1，部分 ISO-639-3），如 `zh` / `en` / `ja`；不设置则自动检测。
</ParamField>

<ParamField body="response_format" type="string" default="json">
  响应格式：`json` / `text` / `verbose_json`。
</ParamField>

<ParamField body="stream" type="boolean" default="false">
  是否流式返回（lite 不支持）。
</ParamField>

<ParamField body="enable_punctuation" type="boolean" default="false">
  自动标点（仅 asr / pro，deepthink 静默忽略）。
</ParamField>

<ParamField body="enable_speaker_diarization" type="boolean" default="false">
  说话人分离（仅 asr / pro）。
</ParamField>

<ParamField body="max_speakers" type="integer">
  最大说话人数 1–20，配合说话人分离使用（仅 asr-pro 支持）。
</ParamField>

<ParamField body="timestamp_granularities[]" type="string[]">
  时间戳粒度：`word` = 字级 / `segment` = 句级（仅 asr / pro）。
</ParamField>

<ParamField body="target_language" type="string">
  翻译目标语言代码（lite 不支持，pro / deepthink 支持）。
</ParamField>

<ParamField body="hotwords" type="string">
  热词增强，英文逗号分隔（仅 lite）。
</ParamField>

<ParamField body="recognize_mode" type="string" default="auto">
  识别模式：`auto` / `record_only`（仅 deepthink 流式模式生效）。
</ParamField>

<ParamField body="abbreviations" type="boolean" default="false">
  缩写词自动替换。
</ParamField>

## Response

`200` — `application/json`

<ResponseField name="text" type="string">
  识别出的文本内容（所有 `response_format` 均返回）。
</ResponseField>

<ResponseField name="duration" type="number">
  音频时长（秒），`verbose_json` 下返回。
</ResponseField>

<ResponseField name="audio_info" type="object">
  音频元信息，`verbose_json` / 流式下返回。

  <Expandable title="child attributes">
    <ResponseField name="audio_info.duration" type="integer">
      音频时长（ms）。
    </ResponseField>

    <ResponseField name="audio_info.format" type="string">
      音频格式。
    </ResponseField>
  </Expandable>
</ResponseField>

<ResponseField name="segments" type="object[]">
  分段结果（需 `response_format=verbose_json` 或 `timestamp_granularities[]=segment`）。

  <Expandable title="child attributes">
    <ResponseField name="segments.id" type="integer">
      分段索引。
    </ResponseField>

    <ResponseField name="segments.start" type="number">
      起始时间（秒）。
    </ResponseField>

    <ResponseField name="segments.end" type="number">
      结束时间（秒）。
    </ResponseField>

    <ResponseField name="segments.text" type="string">
      本段识别文本。
    </ResponseField>

    <ResponseField name="segments.speaker" type="string">
      说话人标识，需开启 `enable_speaker_diarization`。
    </ResponseField>

    <ResponseField name="segments.translation" type="string">
      翻译结果，需设置 `target_language`。
    </ResponseField>
  </Expandable>
</ResponseField>

<ResponseField name="words" type="object[]">
  字级结果，需设置 `timestamp_granularities[]=word`。

  <Expandable title="child attributes">
    <ResponseField name="words.word" type="string">
      字符。
    </ResponseField>

    <ResponseField name="words.start" type="number">
      起始时间（秒）。
    </ResponseField>

    <ResponseField name="words.end" type="number">
      结束时间（秒）。
    </ResponseField>
  </Expandable>
</ResponseField>

## 响应格式详解

### JSON（默认）

```json theme={null}
{ "text": "识别出的文本内容" }
```

### Text

纯文本，`Content-Type: text/plain`。

```text theme={null}
识别出的文本内容
```

### Verbose JSON

```json theme={null}
{
  "text": "道可道非常道",
  "duration": 2.1,
  "audio_info": { "duration": 5230, "format": "wav" },
  "segments": [
    {
      "id": 0,
      "start": 0.0,
      "end": 2.0,
      "text": "道可道非常道",
      "speaker": "speaker_0",
      "translation": "Translated"
    }
  ],
  "words": [
    { "word": "道", "start": 0.27, "end": 0.51 },
    { "word": "可", "start": 0.57, "end": 0.81 }
  ]
}
```

### 流式响应 (SSE)

`Content-Type: text/event-stream`

```text theme={null}
data: {"delta": {"text": "增量文本"}, "finish_reason": null}
data: {"delta": {"text": "。"}, "finish_reason": "stop", "audio_info": {...}}
data: [DONE]
```

| 字段              | 说明                                   |
| :-------------- | :----------------------------------- |
| `delta.text`    | 本次返回的增量文本                            |
| `finish_reason` | `null`（进行中）/ `stop`（完成）/ `error`（错误） |

## 语言支持

`language` 用于指定音频内容的语言（留空则自动检测）；`target_language` 将识别结果翻译为另一语言。

### 各模型参数支持

| 模型                                    | `language` | `target_language` |
| :------------------------------------ | :--------: | :---------------: |
| `senseaudio-asr-lite-1.5-260319`      |      ✅     |         ❌         |
| `senseaudio-asr-1.5-260319`           |      ✅     |         ❌         |
| `senseaudio-asr-pro-1.5-260319`       |      ✅     |         ✅         |
| `senseaudio-asr-deepthink-1.5-260319` |      ✅     |         ✅         |

<Warning>
  部分模型仅支持 `language` 或 `target_language`，请以上表为准。
</Warning>

### senseaudio-asr-lite-1.5-260319 支持语种

| 代码         | 语言    | 代码   | 语言    | 代码    | 语言     |
| :--------- | :---- | :--- | :---- | :---- | :----- |
| `zh`       | 中文    | `en` | 英文    | `yue` | 粤语     |
| `ja`       | 日文    | `ko` | 韩文    | `vi`  | 越南语    |
| `id`       | 印尼语   | `th` | 泰语    | `ms`  | 马来语    |
| `tl`/`fil` | 菲律宾语  | `ar` | 阿拉伯语  | `hi`  | 印地语    |
| `bg`       | 保加利亚语 | `hr` | 克罗地亚语 | `cs`  | 捷克语    |
| `da`       | 丹麦语   | `nl` | 荷兰语   | `et`  | 爱沙尼亚语  |
| `fi`       | 芬兰语   | `el` | 希腊语   | `hu`  | 匈牙利语   |
| `ga`       | 爱尔兰语  | `lv` | 拉脱维亚语 | `lt`  | 立陶宛语   |
| `mt`       | 马耳他语  | `pl` | 波兰语   | `pt`  | 葡萄牙语   |
| `ro`       | 罗马尼亚语 | `sk` | 斯洛伐克语 | `sl`  | 斯洛文尼亚语 |
| `sv`       | 瑞典语   |      |       |       |        |

### senseaudio-asr-1.5-260319 / senseaudio-asr-pro-1.5-260319 支持语种

| 代码   | 语言   | 代码    | 语言   | 代码   | 语言   |
| :--- | :--- | :---- | :--- | :--- | :--- |
| `ar` | 阿拉伯语 | `yue` | 粤语   | `zh` | 中文   |
| `nl` | 荷兰语  | `en`  | 英文   | `fr` | 法语   |
| `de` | 德语   | `id`  | 印尼语  | `it` | 意大利语 |
| `ja` | 日文   | `ko`  | 韩文   | `ms` | 马来语  |
| `pt` | 葡萄牙语 | `ru`  | 俄语   | `es` | 西班牙语 |
| `th` | 泰语   | `tr`  | 土耳其语 | `ur` | 乌尔都语 |
| `vi` | 越南语  |       |      |      |      |

### senseaudio-asr-deepthink-1.5-260319 支持语种

同 `senseaudio-asr-1.5-260319` / `senseaudio-asr-pro-1.5-260319` 表，用于翻译输出。

## 各模型调用示例

### 通过 file 直传（≤ 10 MB）

```bash theme={null}
curl https://api.senseaudio.cn/v1/audio/transcriptions \
  -H "Authorization: Bearer $SENSEAUDIO_API_KEY" \
  -F file="@meeting.wav" \
  -F model="senseaudio-asr-1.5-260319" \
  -F language="zh"
```

### 通过 file\_id 转写（大文件）

先调用 [文件上传](/api-reference/endpoint/files/upload)（`purpose=speech_to_text`）拿到 `file_id`，再用本接口转写：

```bash theme={null}
# 1) 上传大文件
curl -X POST https://api.senseaudio.cn/v1/files/upload \
  -H "Authorization: $SENSEAUDIO_API_KEY" \
  -F "purpose=speech_to_text" \
  -F "file=@/path/to/long_audio.wav"

# 2) 用返回的 file_id 转写（可替代 -F file="@..."）
curl https://api.senseaudio.cn/v1/audio/transcriptions \
  -H "Authorization: Bearer $SENSEAUDIO_API_KEY" \
  -F file_id="file-E4avzR574Mm42YoCytmiND" \
  -F model="senseaudio-asr-1.5-260319" \
  -F language="zh"
```

<Note>
  `file` 与 `file_id` 同时传入时，服务端优先使用 `file_id`。
</Note>

### senseaudio-asr-lite-1.5-260319

轻量级模型。**热词增强示例**：

```bash theme={null}
curl https://api.senseaudio.cn/v1/audio/transcriptions \
  -H "Authorization: Bearer $SENSEAUDIO_API_KEY" \
  -F file="@meeting.wav" \
  -F model="senseaudio-asr-lite-1.5-260319" \
  -F language="zh" \
  -F hotwords="张三,李四,项目Alpha,季度复盘"
```

```json theme={null}
{ "text": "张三和李四负责项目Alpha的季度复盘工作" }
```

### senseaudio-asr-1.5-260319

标准模型。**字级 / 句级时间戳示例**：

```bash theme={null}
curl https://api.senseaudio.cn/v1/audio/transcriptions \
  -H "Authorization: Bearer $SENSEAUDIO_API_KEY" \
  -F file="@interview.wav" \
  -F model="senseaudio-asr-1.5-260319" \
  -F response_format="verbose_json" \
  -F "timestamp_granularities[]=word"
```

### senseaudio-asr-pro-1.5-260319

专业版。**说话人分离 + 字级时间戳 + 翻译**：

```bash theme={null}
curl https://api.senseaudio.cn/v1/audio/transcriptions \
  -H "Authorization: Bearer $SENSEAUDIO_API_KEY" \
  -F file="@meeting.wav" \
  -F model="senseaudio-asr-pro-1.5-260319" \
  -F response_format="verbose_json" \
  -F enable_speaker_diarization="true" \
  -F max_speakers="4" \
  -F "timestamp_granularities[]=word" \
  -F target_language="en"
```

### senseaudio-asr-deepthink-1.5-260319

深度理解模型。**翻译示例**：

```bash theme={null}
curl https://api.senseaudio.cn/v1/audio/transcriptions \
  -H "Authorization: Bearer $SENSEAUDIO_API_KEY" \
  -F file="@complex_audio.mp3" \
  -F model="senseaudio-asr-deepthink-1.5-260319" \
  -F target_language="en"
```

```json theme={null}
{ "text": "The weather is nice today, suitable for going out for a walk." }
```

## 错误处理

错误时返回非 200 状态码，响应体：

```json theme={null}
{
  "code": "invalid",
  "message": "file is required"
}
```

| HTTP | `code`             | 说明     |
| :--- | :----------------- | :----- |
| 400  | `invalid`          | 参数错误   |
| 429  | `rate_limit_error` | 请求频率过高 |
| 500  | `internal_error`   | 服务端错误  |

## 相关指南

* [语音识别介绍](/guides/asr/overview)
* [文件上传](/api-reference/endpoint/files/upload)
* [流式 ASR](/api-reference/endpoint/asr/realtime)
* [语音识别 WebSocket](/api-reference/endpoint/asr/websocket)
* [音频质量检测](/api-reference/endpoint/asr/analysis)
* [语音识别历史](/api-reference/endpoint/asr/records)
* [计费说明](/guides/account/billing)


## OpenAPI

````yaml api-reference/endpoint/asr/transcriptions.openapi.json POST /v1/audio/transcriptions
openapi: 3.1.0
info:
  title: SenseAudio - 语音识别转写
  version: 1.0.0
servers:
  - url: https://api.senseaudio.cn
    description: 生产环境
security:
  - bearerAuth: []
paths:
  /v1/audio/transcriptions:
    post:
      tags:
        - ASR
      summary: SenseASR 语音识别（HTTP）
      description: >-
        基于 HTTP
        协议的语音识别服务，支持多种模型：lite（极速版）、标准版、pro（专业版）、deepthink（深度版）。支持多语言、说话人分离、字/句级时间戳、翻译等功能。可直接传
        file（≤ 10 MB），或传 file_id（先通过
        /v1/files/upload，purpose=speech_to_text）。两者都传时优先使用 file_id。
      operationId: audioTranscriptions
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required:
                - model
              oneOf:
                - required:
                    - file
                  title: 使用 file 直传
                - required:
                    - file_id
                  title: 使用 file_id
              properties:
                file:
                  type: string
                  format: binary
                  description: >-
                    【方式一】直接上传音频（wav/mp3/ogg/flac/aac/m4a/mp4 等），≤ 10 MB。与
                    file_id 二选一；同时传入时优先 file_id。
                  example: '@audio.mp3'
                file_id:
                  type: string
                  description: >-
                    【方式二】已上传文件 ID（先调
                    /v1/files/upload，purpose=speech_to_text）。可替代 file；与 file
                    同时存在时优先本字段。
                  example: file-E4avzR574Mm42YoCytmiND
                model:
                  type: string
                  description: 模型名称
                  enum:
                    - senseaudio-asr-lite-1.5-260319
                    - senseaudio-asr-1.5-260319
                    - senseaudio-asr-pro-1.5-260319
                    - senseaudio-asr-deepthink-1.5-260319
                language:
                  type: string
                  description: 语言代码（ISO-639-1/3），如 zh/en/ja，不设置会自动检测
                  example: zh
                response_format:
                  type: string
                  description: 响应格式
                  default: json
                  enum:
                    - json
                    - text
                    - verbose_json
                stream:
                  type: boolean
                  description: 是否流式返回（lite 不支持）
                  default: false
                enable_punctuation:
                  type: boolean
                  description: 自动标点（仅 asr/pro）
                  default: false
                enable_speaker_diarization:
                  type: boolean
                  description: 说话人分离（仅 asr/pro）
                  default: false
                max_speakers:
                  type: integer
                  description: 最大说话人数 1-20（仅 asr-pro）
                  minimum: 1
                  maximum: 20
                  example: 4
                timestamp_granularities[]:
                  type: array
                  description: word=字级 / segment=句级（仅 asr/pro）
                  items:
                    type: string
                    enum:
                      - word
                      - segment
                target_language:
                  type: string
                  description: 翻译目标语言代码（lite 不支持）
                  example: en
                hotwords:
                  type: string
                  description: 热词增强，逗号分隔（仅 lite）
                  example: 张三,李四,项目Alpha
                recognize_mode:
                  type: string
                  description: 识别模式（仅 deepthink 流式）
                  default: auto
                  enum:
                    - auto
                    - record_only
                abbreviations:
                  type: boolean
                  description: 缩写词自动替换
                  default: false
            examples:
              withFile:
                summary: 使用 file 直传
                value:
                  file: '@audio.mp3'
                  model: senseaudio-asr-1.5-260319
              withFileId:
                summary: 使用 file_id
                value:
                  file_id: file-E4avzR574Mm42YoCytmiND
                  model: senseaudio-asr-1.5-260319
      responses:
        '200':
          description: 识别结果。格式取决于 response_format。
          content:
            application/json:
              schema:
                type: object
                description: json 或 verbose_json 响应
                properties:
                  text:
                    type: string
                    description: 识别出的文本内容
                  duration:
                    type: number
                    description: 识别时长（秒）
                  audio_info:
                    type: object
                    properties:
                      duration:
                        type: integer
                        description: 音频时长 (ms)
                      format:
                        type: string
                        description: 音频格式
                  segments:
                    type: array
                    items:
                      type: object
                      properties:
                        id:
                          type: integer
                        start:
                          type: number
                        end:
                          type: number
                        text:
                          type: string
                        speaker:
                          type: string
                          description: 需开启 enable_speaker_diarization
                        translation:
                          type: string
                          description: 需设置 target_language
                  words:
                    type: array
                    items:
                      type: object
                      properties:
                        word:
                          type: string
                        start:
                          type: number
                        end:
                          type: number
              example:
                text: 欢迎使用我们的语音识别服务，希望能为您提供帮助。
            text/plain:
              schema:
                type: string
                description: 纯文本识别结果（response_format=text）
            text/event-stream:
              schema:
                type: string
                description: '流式 SSE 响应，每行 ''data: {delta, finish_reason}'''
        '400':
          description: 参数错误
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ASRError'
        '429':
          description: 请求频率过高
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ASRError'
        '500':
          description: 服务端错误
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TranscriptionResponse'
components:
  schemas:
    ASRError:
      type: object
      properties:
        code:
          type: string
          description: 错误码
          enum:
            - invalid
            - rate_limit_error
            - internal_error
        message:
          type: string
          description: 错误信息
    TranscriptionResponse:
      type: object
      properties:
        text:
          type: string
        segments:
          type: array
          items:
            type: object
            properties:
              start:
                type: number
              end:
                type: number
              text:
                type: string
              speaker:
                type: string
        base_resp:
          $ref: '#/components/schemas/BaseResp'
    BaseResp:
      type: object
      description: 通用状态结构
      properties:
        status_code:
          type: integer
          description: 0 表示成功，非 0 表示失败
        status_msg:
          type: string
          description: 状态详情
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: API_KEY
      description: 格式：`Bearer <API_KEY>`

````
