> ## Documentation Index
> Fetch the complete documentation index at: https://docs.senseaudio.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 语音合成 (TTS)

export const TtsSynthesizeErrorHeading = () => {
  useEffect(() => {
    const section = document.createElement("section");
    section.className = "api-section prose prose-gray dark:prose-invert mt-8";
    const heading = document.createElement("h4");
    heading.id = "tts-synthesize-error-codes";
    heading.className = "api-section-heading-title border-b pb-2.5 border-gray-100 dark:border-gray-800";
    heading.textContent = "错误详情";
    section.appendChild(heading);
    const table = document.createElement("table");
    table.className = "m-0 min-w-full w-full max-w-none table [&_td]:min-w-[150px] [&_th]:text-left [&_td[data-numeric]]:tabular-nums";
    table.innerHTML = `
      <thead><tr><th>status_msg</th><th>业务含义</th></tr></thead>
      <tbody>
        <tr><td>incorrect API key provided</td><td>提供的 API 密钥不正确</td></tr>
        <tr><td>sample_rate 12345 is not supported, supported: [8000 16000 22050 24000 32000 44100]</td><td>采样率不支持</td></tr>
        <tr><td>input text is too long</td><td>输入文本超过长度限制</td></tr>
        <tr><td>text is required</td><td>输入文本为空或不合法</td></tr>
        <tr><td>input content type is not supported</td><td>没有有效文本；参数、模型、音频格式、采样率、语速等不合法</td></tr>
        <tr><td>no access to the specified voice</td><td>音色不可用或已禁用；音色不存在</td></tr>
        <tr><td>Internal Server Error</td><td>TTS 模型不存在或模型资源不存在</td></tr>
        <tr><td>model does not support this capability</td><td>模型不支持当前能力</td></tr>
        <tr><td>model is required</td><td>模型参数为空或不合法</td></tr>
        <tr><td>audio format aac is not supported</td><td>音频格式不支持</td></tr>
        <tr><td>speed must be between 0.5 and 2.0</td><td>语速超出允许范围</td></tr>
        <tr><td>vol must be between 0 and 10</td><td>音量超出允许范围</td></tr>
        <tr><td>pitch must be between -12 and 12</td><td>音调超出允许范围</td></tr>
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

<TtsSynthesizeErrorHeading />

## 说明

将文本合成为高自然度的语音音频，支持 70+ 系统音色、克隆音色与文生音色。本页介绍非流式合成（`stream=false`），一次性返回完整音频。

* **接入域名**：`https://api.senseaudio.cn`
* **计费单位**：按合成字符数计费，详见 [计费说明](/guides/account/billing)
* **音色入参**：`voice_id` 必须为当前账号可用音色，参考 [音色列表](/guides/voice/catalog)
* **流式 (SSE) 合成**：参考 [语音合成 HTTP 流式](/api-reference/endpoint/tts/synthesize-stream)
* **WebSocket 合成**：参考 [语音合成 WebSocket](/api-reference/endpoint/tts/websocket)

## 相关指南

* [语音合成与音色概览](/guides/tts/overview)
* [自定义音色（音色克隆与文生音色）](/guides/voice/custom)


## OpenAPI

````yaml api-reference/endpoint/tts/synthesize.openapi.json POST /v1/t2a_v2
openapi: 3.1.0
info:
  title: SenseAudio - 语音合成
  version: 1.0.0
servers:
  - url: https://api.senseaudio.cn
    description: 生产环境
security:
  - bearerAuth: []
paths:
  /v1/t2a_v2:
    post:
      tags:
        - Text to Speech
      summary: 语音合成 (TTS)
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TTSRequest'
            example:
              model: sensenova-tts-2.0
              text: 你好，这是来自SenseAudio 的第一条语音
              stream: false
              voice_setting:
                voice_id: female_0033_b
                speed: 1
                vol: 1
                pitch: 0
              audio_setting:
                format: mp3
                sample_rate: 32000
                bitrate: 128000
                channel: 2
      responses:
        '200':
          description: 成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TTSResponse'
        '401':
          description: 鉴权失败
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
components:
  schemas:
    TTSRequest:
      type: object
      required:
        - model
        - text
        - voice_setting
      properties:
        model:
          type: string
          enum:
            - senseaudio-tts-1.5-260319
            - sensenova-tts-2.0
          default: sensenova-tts-2.0
          example: sensenova-tts-2.0
          description: >-
            TTS 模型
            ID。可选值：`senseaudio-tts-1.5-260319`、`sensenova-tts-2.0`。`sensenova-tts-2.0`
            为推荐版本（高清音质，合成更自然逼真，支持克隆与文生音色）；
        text:
          type: string
          description: >-
            合成文本，最大 10000 字符，支持 <break time=ms> 停顿符。其中time 单位为毫秒（ms）、500 表示停顿
            500 毫秒、最小值为 100 毫秒，最大值无限制。
          example: 你好，这是来自 <break time=500> SenseAudio 的第一条语音
        stream:
          type: boolean
          enum:
            - false
          default: false
          example: false
          description: 非流式合成必须为 `false`（流式请参考 语音合成 HTTP 流式 接口）
        voice_setting:
          $ref: '#/components/schemas/VoiceSetting'
        audio_setting:
          $ref: '#/components/schemas/AudioSetting'
        dictionary:
          type: array
          items:
            $ref: '#/components/schemas/DictionaryItem'
          example:
            - original: 好干净
              replacement: '[hao4]干净'
        stream_options:
          type: object
          properties:
            exclude_aggregated_audio:
              type: boolean
              example: false
    TTSResponse:
      type: object
      properties:
        data:
          type: object
          properties:
            audio:
              type: string
              description: hex 编码音频
            status:
              type: integer
              description: 1 合成中 2 合成结束
        extra_info:
          type: object
          properties:
            audio_length:
              type: integer
            audio_sample_rate:
              type: integer
            audio_size:
              type: integer
            bitrate:
              type: integer
            audio_format:
              type: string
            audio_channel:
              type: integer
            word_count:
              type: integer
            usage_characters:
              type: integer
        trace_id:
          type: string
        base_resp:
          $ref: '#/components/schemas/BaseResp'
    ErrorResponse:
      type: object
      properties:
        code:
          type: string
        message:
          type: string
    VoiceSetting:
      type: object
      required:
        - voice_id
      properties:
        voice_id:
          type: string
          description: 系统/克隆/文生音色 ID
          example: female_0033_b
        speed:
          type: number
          default: 1
          description: 语速 [0.5, 2.0]
        vol:
          type: number
          default: 1
          description: 音量 [0.01, 10.0]
        pitch:
          type: integer
          default: 0
          description: 音调 [-12, 12]
        latex_read:
          type: boolean
          default: false
          description: >-
            控制是否朗读 latex 公式，默认为 false。


            - 请求中的公式需要在公式的首尾加上 `$$`。

            - 请求中公式若有 `\`，需转义成 `\\`。


            示例：动能公式


            <img src="/images/tts/latex-read-example.png" alt="动能公式" width="128"
            />


            表示为：`$$E_k = \\frac{1}{2} m v^2$$`
    AudioSetting:
      type: object
      properties:
        format:
          type: string
          enum:
            - mp3
            - wav
            - pcm
            - flac
          default: mp3
        sample_rate:
          type: integer
          enum:
            - 8000
            - 16000
            - 22050
            - 24000
            - 32000
            - 44100
          default: 32000
        bitrate:
          type: integer
          enum:
            - 32000
            - 64000
            - 128000
            - 256000
          default: 128000
        channel:
          type: integer
          enum:
            - 1
            - 2
          default: 2
    DictionaryItem:
      type: object
      properties:
        original:
          type: string
          example: 好干净
        replacement:
          type: string
          example: '[hao4]干净'
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
