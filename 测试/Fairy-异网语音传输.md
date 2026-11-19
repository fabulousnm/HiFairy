# Fairy 电脑到手机异网语音链路测试

2026-11-19。独立于正式 Android 应用的实验。电脑端保持原版 Fairy GPT-SoVITS v2 模型常驻，预热后用 WebSocket 发送 PCM 分段；手机浏览器可在移动数据下播放和显示连接、首段到达、完整接收时间。实现与运行方法见《../voice-pc/README.md》。

临时公网入口使用 Cloudflare Quick Tunnel 与随机网址，不需要域名和付费账户。服务仅绑定电脑回环地址，单次测试令牌保存在 Git 忽略目录，限制文本长度并串行处理。临时通道无运行保证，网址持有者可以访问测试页；正式部署需要长期稳定的身份验证、加密传输、限流和进程管理。

| 路径 | 连接 | 首段 PCM 到达 | 完整接收 | 电脑首段生成 | 电脑完整生成 | 块数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| PC → 本机 WebSocket | 115 ms | 1243 ms | 3094 ms | 1117 ms | 2970 ms | 2 |
| PC → 公网入口 → 同一 PC | 771 ms | 2581 ms | 4031 ms | 1002 ms | 2570 ms | 2 |

测试文本为「主人，语音模块已就绪。今天有什么任务需要本系统处理？」；每条只测了一次，不能据此推断稳定延迟或移动网络表现。第二行电脑访问公网入口时，本机 DNS 把测试域名解析为无法握手的私网地址，命令行测试通过指定 Cloudflare 公网 IP 并保留原域名的 TLS SNI 完成。手机移动数据实测仍待用户返回数据。浏览器中的“首段排播”是交给 Web Audio 的时间，不等于扬声器物理出声时间。

Cloudflare 官方文档说明 [Quick Tunnel 是临时测试用途且无运行保证](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)，[WebSocket 可经 Tunnel 使用](https://developers.cloudflare.com/cloudflare-one/faq/cloudflare-tunnels-faq/)。
