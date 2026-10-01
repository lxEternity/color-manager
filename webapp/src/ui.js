/** 轻量 UI 工具：toast / 确认弹窗（App.vue 挂载宿主） */

let toastEl = null
let toastTimer = 0

export function showToast(msg) {
  if (!toastEl) {
    toastEl = document.createElement('div')
    toastEl.id = 'cf-toast'
    document.body.appendChild(toastEl)
  }
  toastEl.textContent = msg
  toastEl.classList.add('show')
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => toastEl && toastEl.classList.remove('show'), 2200)
}

/** 单选弹窗 → Promise<index>（取消 -1） */
export function pickOptions(options, checked, title) {  return new Promise(resolve => {
    const mask = document.createElement('div')
    mask.className = 'cf-mask'
    const items = options.map((o, i) =>
      '<div class="cf-pick-item" data-i="' + i + '">' +
      '<span>' + o + '</span><span class="cf-pick-mark">' + (i === checked ? '✓' : '') + '</span></div>').join('')
    mask.innerHTML =
      '<div class="cf-dialog">' +
      (title ? '<div class="cf-dialog-title"></div>' : '') +
      '<div class="cf-pick-list">' + items + '</div>' +
      '<div class="cf-dialog-btns">' +
      '<button class="cf-dialog-btn cancel">取消</button>' +
      '</div></div>'
    if (title) mask.querySelector('.cf-dialog-title').textContent = title
    const done = v => { mask.remove(); resolve(v) }
    mask.querySelectorAll('.cf-pick-item').forEach(el => {
      el.onclick = () => done(parseInt(el.dataset.i, 10))
    })
    mask.querySelector('.cancel').onclick = () => done(-1)
    document.body.appendChild(mask)
  })
}

/** 确认弹窗 → Promise<boolean> */
export function confirmBox(title, message, okText, cancelText) {
  return new Promise(resolve => {
    const mask = document.createElement('div')
    mask.className = 'cf-mask'
    mask.innerHTML =
      '<div class="cf-dialog">' +
      (title ? '<div class="cf-dialog-title"></div>' : '') +
      '<div class="cf-dialog-msg"></div>' +
      '<div class="cf-dialog-btns">' +
      '<button class="cf-dialog-btn cancel"></button>' +
      '<button class="cf-dialog-btn ok"></button>' +
      '</div></div>'
    if (title) mask.querySelector('.cf-dialog-title').textContent = title
    mask.querySelector('.cf-dialog-msg').textContent = message || ''
    mask.querySelector('.ok').textContent = okText || '确定'
    mask.querySelector('.cancel').textContent = cancelText || '取消'
    const done = v => { mask.remove(); resolve(v) }
    mask.querySelector('.ok').onclick = () => done(true)
    mask.querySelector('.cancel').onclick = () => done(false)
    mask.onclick = e => { if (e.target === mask) done(false) }
    document.body.appendChild(mask)
  })
}

