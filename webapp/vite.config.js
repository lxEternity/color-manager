import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [
    vue(),
    // 老 WebView（Android 8.0 / Chrome 58）兼容：
    // 1. <script type="module"> 在 Chrome <61 被静默忽略 → 黑屏。
    //    单 chunk 产物无 import/export，改普通 defer 脚本即可执行。
    // 2. crossorigin 属性在 file:// 下触发 CORS 请求，旧 WebView 可能拦截资源。
    {
      name: 'legacy-webview-html',
      // 必须为 post：vite:build-html（核心构建插件）在 generateBundle 阶段
      // 才产出 html 资产，普通插件先于它执行时 bundle 里还没有 index.html
      enforce: 'post',
      apply: 'build',
      generateBundle (_, bundle) {
        const html = bundle['index.html']
        if (!html || typeof html.source !== 'string') return
        let s = html.source
        s = s.replace(/<script type="module"([^>]*)>/g, '<script defer$1>')
        s = s.replace(/ crossorigin/g, '')
        html.source = s
      }
    }
  ],
  // WebView 从 assets 目录加载，必须用相对路径
  base: './',
  build: {
    // Android 8.0 WebView（Chrome 58）兼容：不超过 ES2017
    target: 'es2017',
    assetsInlineLimit: 100000000,
    rollupOptions: {
      output: {
        // WebView 从 assets 本地加载，固定文件名便于打包
        entryFileNames: 'app.js',
        assetFileNames: 'app.[ext]'
      }
    }
  }
})
