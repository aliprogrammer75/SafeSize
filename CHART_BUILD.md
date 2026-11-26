# SafeSize chart engine

Run `bash scripts/prepare-chart.sh` before a local Android build (requires npm). CI runs this automatically. The pinned KLineChart 9.8.12 UMD bundle and license are packaged in the APK; no runtime CDN or TradingView account is required. Only public Bitunix market data crosses the chart boundary. This is a native app with a sandboxed local chart WebView. Chart interactions and data-feed accuracy must be validated on device before use for real orders.
