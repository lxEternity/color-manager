import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
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
