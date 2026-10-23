// Builds a phone around a capture: the emulator's own status bar is covered by a clean one in the colour of the
// app's top bar (sampled just below it), and the navigation bar is cut off (the screen ends at y = 2094).
const AIRPLANE = '<svg viewBox="0 0 24 24"><path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z"/></svg>';
const BATTERY = '<svg viewBox="0 0 28 14"><rect x="0.75" y="0.75" width="23.5" height="12.5" rx="3.5" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.5"/><rect x="2.75" y="2.75" width="19.5" height="8.5" rx="2"/><rect x="25.5" y="4.5" width="2" height="5" rx="1" opacity="0.5"/></svg>';

function sampleTop(image) {
  const canvas = document.createElement('canvas');
  canvas.width = 1; canvas.height = 1;
  const context = canvas.getContext('2d');
  context.drawImage(image, 540, 74, 1, 1, 0, 0, 1, 1);
  const [r, g, b] = context.getImageData(0, 0, 1, 1).data;
  return { css: `rgb(${r}, ${g}, ${b})`, light: 0.299 * r + 0.587 * g + 0.114 * b > 150 };
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('missing ' + src));
    image.src = src;
  });
}

async function buildPhone({ screen, width, bezel = 14, radius, pill = true }) {
  const src = `screens/${screen}.png`;
  const image = await loadImage(src);
  const top = sampleTop(image);
  const phone = document.createElement('div');
  phone.className = 'phone';
  phone.style.setProperty('--w', width + 'px');
  phone.style.setProperty('--bezel', bezel + 'px');
  phone.style.setProperty('--radius', (radius ?? Math.round(width * 0.105)) + 'px');
  phone.innerHTML = `
    <div class="screen" style="background-image:url('${src}')">
      <div class="statusbar" style="background:${top.css};color:${top.light ? '#111' : '#fff'}">
        <span>9:41</span>
        <span class="icons">${AIRPLANE}${BATTERY}</span>
      </div>
      <div class="camera"></div>
      ${pill ? '<div class="pill"></div>' : ''}
    </div>`;
  return phone;
}
