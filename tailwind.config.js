/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/cljs/**/*.cljs",
    "./resources/public/index.html"
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        layer: {
          domain:  '#8b5cf6',
          concept: '#3b82f6',
          fact:    '#22c55e',
          episode: '#f97316'
        }
      }
    }
  },
  plugins: []
}
