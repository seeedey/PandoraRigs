// PandoraRigs Maker: the plugin's SkinPack.java, in the browser.
// Same skeleton, same box unwrap, same JSON, so a pack built here wears exactly like one the plugin builds.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const NAMESPACE = 'pandorarigs';
const UV = 16 / 64;
const OVERLAY_INFLATE = 0.25;
const ID = /^[a-z0-9_]{1,32}$/;

// name, size w h d, base uv, overlay uv, hangs up off its pivot, shoulder lift, pivot in skin pixels.
// Pivots in model-space handedness: the figure faces -z with its right side at +x (Rig.java turns it in game).
const BONES = [
  { name: 'head',      w: 8, h: 8,  d: 8, bu: 0,  bv: 0,  ou: 32, ov: 0,  up: true,  lift: 0, pivot: [0, 24, 0] },
  { name: 'body',      w: 8, h: 12, d: 4, bu: 16, bv: 16, ou: 16, ov: 32, up: true,  lift: 0, pivot: [0, 12, 0] },
  { name: 'arm_right', w: 3, h: 12, d: 4, bu: 40, bv: 16, ou: 40, ov: 32, up: false, lift: 2, pivot: [5.5, 22, 0] },
  { name: 'arm_left',  w: 3, h: 12, d: 4, bu: 32, bv: 48, ou: 48, ov: 48, up: false, lift: 2, pivot: [-5.5, 22, 0] },
  { name: 'leg_right', w: 4, h: 12, d: 4, bu: 0,  bv: 16, ou: 0,  ov: 32, up: false, lift: 0, pivot: [2, 12, 0] },
  { name: 'leg_left',  w: 4, h: 12, d: 4, bu: 16, bv: 48, ou: 0,  ov: 48, up: false, lift: 0, pivot: [-2, 12, 0] },
];

// ---------------------------------------------------------------- skins in

function canvas64() {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  return c;
}

/** Any accepted skin to a 64x64 canvas, old 64x32 skins given their missing left limbs. */
function toCanvas(img) {
  if (img.width !== 64 || (img.height !== 64 && img.height !== 32)) {
    throw new Error(`A skin is 64×64 (or old 64×32). This one is ${img.width}×${img.height}.`);
  }
  const c = canvas64();
  const g = c.getContext('2d');
  g.drawImage(img, 0, 0);
  if (img.height === 32) {
    g.drawImage(c, 0, 16, 16, 16, 16, 48, 16, 16);
    g.drawImage(c, 40, 16, 16, 16, 32, 48, 16, 16);
  }
  return c;
}

/** A slim skin leaves the last two columns of the right arm's texture empty; a classic one fills them. */
function looksSlim(c) {
  const px = c.getContext('2d').getImageData(54, 20, 2, 12).data;
  for (let i = 3; i < px.length; i += 4) if (px[i] !== 0) return false;
  return true;
}

/** Classic arms to slim, both layers: drop the column nearest the body from front, back, top and bottom. */
function toSlim(src) {
  const out = canvas64();
  const g = out.getContext('2d');
  g.drawImage(src, 0, 0);
  const s = src.getContext('2d').getImageData(0, 0, 64, 64);
  const o = g.getImageData(0, 0, 64, 64);
  const get = (x, y) => s.data.slice((y * 64 + x) * 4, (y * 64 + x) * 4 + 4);
  const set = (x, y, p) => o.data.set(p, (y * 64 + x) * 4);
  const narrow = (sx, sy, w, h, drop, dx, dy) => {
    let col = 0;
    for (let x = 0; x < w; x++) {
      if (x === drop) continue;
      for (let y = 0; y < h; y++) set(dx + col, dy + y, get(sx + x, sy + y));
      col++;
    }
  };
  const arm = (u, v, right) => {
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) set(u + x, v + y, [0, 0, 0, 0]);
    const inF = right ? 3 : 0, inB = right ? 0 : 3;
    narrow(u + 4, v, 4, 4, inF, u + 4, v);
    narrow(u + 8, v, 4, 4, inF, u + 7, v);
    narrow(u, v + 4, 4, 12, -1, u, v + 4);
    narrow(u + 4, v + 4, 4, 12, inF, u + 4, v + 4);
    narrow(u + 8, v + 4, 4, 12, -1, u + 7, v + 4);
    narrow(u + 12, v + 4, 4, 12, inB, u + 11, v + 4);
  };
  arm(40, 16, true); arm(40, 32, true); arm(32, 48, false); arm(48, 48, false);
  g.putImageData(o, 0, 0);
  return out;
}

// ---------------------------------------------------------------- the pack's JSON (SkinPack.java, line for line)

const n = v => v.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');

function boxOf(b, inflate) {
  const x0 = 8 - b.w / 2 - inflate, x1 = 8 + b.w / 2 + inflate;
  const z0 = 8 - b.d / 2 - inflate, z1 = 8 + b.d / 2 + inflate;
  const y0 = b.up ? 8 - inflate + b.lift : 8 - b.h - inflate + b.lift;
  const y1 = b.up ? 8 + b.h + inflate + b.lift : 8 + inflate + b.lift;
  return { x0, x1, y0, y1, z0, z1 };
}

function facesOf(b, u, v) {
  const { w, h, d } = b;
  return {
    north: [u + d, v + d, u + d + w, v + d + h],
    south: [u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h],
    east: [u, v + d, u + d, v + d + h],
    west: [u + d + w, v + d, u + 2 * d + w, v + d + h],
    up: [u + d, v, u + d + w, v + d],
    down: [u + d + w, v + d, u + d + 2 * w, v],
  };
}

function elementJson(b, u, v, inflate) {
  const k = boxOf(b, inflate);
  const f = facesOf(b, u, v);
  const face = side => `"${side}": {"uv": [${f[side].map(x => n(x * UV)).join(', ')}], "texture": "#skin"}`;
  return `{"from": [${n(k.x0)}, ${n(k.y0)}, ${n(k.z0)}], "to": [${n(k.x1)}, ${n(k.y1)}, ${n(k.z1)}], "faces": {`
    + ['north', 'south', 'east', 'west', 'up', 'down'].map(face).join(', ') + '}}';
}

function modelJson(id, b) {
  const tex = `${NAMESPACE}:item/${id}`;
  return `{"textures": {"skin": "${tex}", "particle": "${tex}"}, "elements": [`
    + elementJson(b, b.bu, b.bv, 0) + ', ' + elementJson(b, b.ou, b.ov, OVERLAY_INFLATE) + ']}';
}

const MCMETA = `{
  "pack": {
    "description": "PandoraRigs — rig bone models",
    "pack_format": 75,
    "supported_formats": {"min_inclusive": 46, "max_inclusive": 99},
    "min_format": 46,
    "max_format": 99
  }
}
`;

const pngBlob = c => new Promise(r => c.toBlob(r, 'image/png'));
const FIXED = new Date(315532800000);

async function buildPack(rigs) {
  const zip = new JSZip();
  zip.file('pack.mcmeta', MCMETA, { date: FIXED });
  for (const r of rigs) {
    zip.file(`assets/${NAMESPACE}/textures/item/${r.id}.png`, await pngBlob(r.skin), { date: FIXED });
    for (const b of BONES) {
      const name = `${r.id}_${b.name}`;
      zip.file(`assets/${NAMESPACE}/models/item/${name}.json`, modelJson(r.id, b), { date: FIXED });
      zip.file(`assets/${NAMESPACE}/items/${name}.json`,
        `{"model": {"type": "minecraft:model", "model": "${NAMESPACE}:item/${name}"}}`, { date: FIXED });
    }
  }
  return zip.generateAsync({ type: 'blob', compression: 'DEFLATE' });
}

async function buildSkins(rigs) {
  const zip = new JSZip();
  for (const r of rigs) zip.file(`${r.id}.png`, await pngBlob(r.skin), { date: FIXED });
  return zip.generateAsync({ type: 'blob', compression: 'DEFLATE' });
}

function download(blob, name) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}

// ---------------------------------------------------------------- the preview

const stage = document.getElementById('stage');
const renderer = new THREE.WebGLRenderer({ canvas: stage, antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(2, window.devicePixelRatio));
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(32, 4 / 5, 1, 1000);
camera.position.set(-30, 26, -70);
const controls = new OrbitControls(camera, stage);
controls.target.set(0, 16, 0);
controls.enablePan = false;
controls.minDistance = 30;
controls.maxDistance = 160;
scene.add(new THREE.HemisphereLight(0xffffff, 0x8877aa, 2.4));
const sun = new THREE.DirectionalLight(0xffffff, 1.4);
sun.position.set(-20, 40, -30);
scene.add(sun);

const floor = new THREE.Mesh(new THREE.CircleGeometry(16, 48),
  new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.12 }));
floor.rotation.x = -Math.PI / 2;
scene.add(floor);

const figure = new THREE.Group();
scene.add(figure);
const joints = {};
let texture = null;

// Each face's corners as seen from outside: top-left, top-right, bottom-right, bottom-left. North (-z) is the front.
function corners(k, side) {
  const { x0, x1, y0, y1, z0, z1 } = k;
  switch (side) {
    case 'north': return [[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]];
    case 'south': return [[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]];
    case 'east':  return [[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]];
    case 'west':  return [[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]];
    case 'up':    return [[x1, y1, z1], [x0, y1, z1], [x0, y1, z0], [x1, y1, z0]];
    case 'down':  return [[x1, y0, z1], [x0, y0, z1], [x0, y0, z0], [x1, y0, z0]];
  }
}

function boneGeometry(b, u, v, inflate) {
  // Model space has the pivot at (8, 8, 8); here it sits at the origin. The figure faces -z, its right side at +x.
  const raw = boxOf(b, inflate);
  const k = { x0: raw.x0 - 8, x1: raw.x1 - 8, y0: raw.y0 - 8, y1: raw.y1 - 8, z0: raw.z0 - 8, z1: raw.z1 - 8 };
  const f = facesOf(b, u, v);
  const pos = [], uvs = [], idx = [];
  for (const side of ['north', 'south', 'east', 'west', 'up', 'down']) {
    const c = corners(k, side);
    const [u0, v0, u1, v1] = f[side];
    const tc = [[u0, v0], [u1, v0], [u1, v1], [u0, v1]];
    const base = pos.length / 3;
    for (let i = 0; i < 4; i++) {
      pos.push(...c[i]);
      uvs.push(tc[i][0] / 64, 1 - tc[i][1] / 64);
    }
    idx.push(base, base + 2, base + 1, base, base + 3, base + 2);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

function showSkin(skin) {
  figure.clear();
  if (texture) texture.dispose();
  texture = new THREE.CanvasTexture(skin);
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestFilter;
  texture.colorSpace = THREE.SRGBColorSpace;
  const base = new THREE.MeshLambertMaterial({ map: texture, alphaTest: 0.5, side: THREE.DoubleSide });
  const over = new THREE.MeshLambertMaterial({ map: texture, alphaTest: 0.5, side: THREE.DoubleSide });
  for (const b of BONES) {
    const j = new THREE.Group();
    j.position.set(...b.pivot);
    j.add(new THREE.Mesh(boneGeometry(b, b.bu, b.bv, 0), base));
    j.add(new THREE.Mesh(boneGeometry(b, b.ou, b.ov, OVERLAY_INFLATE), over));
    joints[b.name] = j;
  }
  // Head and arms ride the body, as in the rig.
  const body = joints.body;
  for (const name of ['head', 'arm_right', 'arm_left']) {
    const j = joints[name];
    j.position.sub(body.position);
    body.add(j);
  }
  figure.add(body, joints.leg_right, joints.leg_left);
}

let anim = 'walk';
const clock = new THREE.Clock();
function frame() {
  const t = clock.getElapsedTime();
  if (joints.body) {
    const J = joints;
    for (const k in J) J[k].rotation.set(0, 0, 0);
    const breath = Math.sin(t * 2) * 0.03;
    J.arm_right.rotation.z = -0.07 - breath;
    J.arm_left.rotation.z = 0.07 + breath;
    if (anim === 'walk') {
      const s = Math.sin(t * 5.5) * 0.6;
      J.leg_right.rotation.x = s;
      J.leg_left.rotation.x = -s;
      J.arm_right.rotation.x = -s * 0.8;
      J.arm_left.rotation.x = s * 0.8;
      figure.position.y = Math.abs(Math.cos(t * 5.5)) * 0.6;
    } else if (anim === 'swing') {
      const p = (t % 1.1) / 1.1;
      const e = p < 0.3 ? p / 0.3 : Math.max(0, 1 - (p - 0.3) / 0.5);
      J.arm_right.rotation.x = -2.2 * e + (p < 0.3 ? 0 : 0.6 * e);
      J.body.rotation.y = -0.35 * e;
      figure.position.y = 0;
    } else {
      J.head.rotation.y = Math.sin(t * 0.6) * 0.4;
      J.head.rotation.x = Math.sin(t * 0.9) * 0.08;
      figure.position.y = 0;
    }
  }
  const w = stage.clientWidth, h = stage.clientHeight;
  if (stage.width !== Math.round(w * renderer.getPixelRatio()) || stage.height !== Math.round(h * renderer.getPixelRatio())) {
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  controls.update();
  renderer.render(scene, camera);
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

// ---------------------------------------------------------------- the page

const $ = id => document.getElementById(id);
const rigs = [];
let pending = null; // { raw: canvas, name }
let arms = 'auto';

function seg(el, on) {
  el.addEventListener('click', e => {
    const b = e.target.closest('button');
    if (!b) return;
    for (const x of el.querySelectorAll('button')) x.setAttribute('aria-pressed', String(x === b));
    on(b.dataset.v);
  });
}
seg($('armSeg'), v => { arms = v; refreshPending(); });
seg($('animSeg'), v => { anim = v; });

function finalSkin(raw) {
  const slim = arms === 'auto' ? looksSlim(raw) : arms === 'slim';
  return { skin: slim ? raw : toSlim(raw), slim };
}

function refreshPending() {
  if (!pending) return;
  const { skin, slim } = finalSkin(pending.raw);
  pending.skin = skin;
  $('armNote').textContent = arms === 'auto'
    ? (slim ? 'Looks slim; used as it is.' : 'Looks classic; narrowed to slim.') : '';
  showSkin(skin);
  validate();
}

function validate() {
  const id = $('rigId').value.trim();
  let msg = '';
  if (id && !ID.test(id)) msg = 'Use lowercase letters, digits and _ only (up to 32).';
  else if (rigs.some(r => r.id === id)) msg = `There is already a rig called ${id}.`;
  $('err').textContent = msg;
  $('add').disabled = !pending || !id || !!msg;
}

async function load(file) {
  $('err').textContent = '';
  try {
    if (!file || file.type !== 'image/png') throw new Error('That is not a PNG.');
    const img = await createImageBitmap(file);
    pending = { raw: toCanvas(img) };
    if (!$('rigId').value) {
      $('rigId').value = file.name.replace(/\.png$/i, '').toLowerCase().replace(/[^a-z0-9_]+/g, '_').slice(0, 32);
    }
    refreshPending();
  } catch (e) {
    pending = null;
    $('err').textContent = e.message;
    validate();
  }
}

$('file').addEventListener('change', e => load(e.target.files[0]));
const drop = $('drop');
drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', e => { e.preventDefault(); drop.classList.remove('over'); load(e.dataTransfer.files[0]); });
$('rigId').addEventListener('input', validate);

$('add').addEventListener('click', () => {
  rigs.push({ id: $('rigId').value.trim(), skin: pending.skin });
  pending = null;
  $('rigId').value = '';
  $('file').value = '';
  $('armNote').textContent = '';
  render(rigs.length - 1);
  validate();
});

function render(selected) {
  const ul = $('rigs');
  ul.innerHTML = '';
  rigs.forEach((r, i) => {
    const li = document.createElement('li');
    if (i === selected) li.className = 'on';
    const face = document.createElement('canvas');
    face.width = face.height = 8;
    const g = face.getContext('2d');
    g.drawImage(r.skin, 8, 8, 8, 8, 0, 0, 8, 8);
    g.drawImage(r.skin, 40, 8, 8, 8, 0, 0, 8, 8);
    const id = document.createElement('span');
    id.className = 'id';
    id.textContent = r.id;
    const rm = document.createElement('button');
    rm.textContent = 'Remove';
    rm.setAttribute('aria-label', `Remove ${r.id}`);
    rm.addEventListener('click', e => { e.stopPropagation(); rigs.splice(i, 1); render(-1); validate(); });
    li.append(face, id, rm);
    li.addEventListener('click', () => { showSkin(r.skin); render(i); });
    ul.append(li);
  });
  if (selected >= 0 && rigs[selected]) showSkin(rigs[selected].skin);
  $('empty').hidden = rigs.length > 0;
  $('dlPack').disabled = $('dlSkins').disabled = rigs.length === 0;
}

$('dlPack').addEventListener('click', async () => download(await buildPack(rigs), 'rigs-pack.zip'));
$('dlSkins').addEventListener('click', async () => download(await buildSkins(rigs), 'skins.zip'));

// Something to look at before a skin is dropped: Steve-ish placeholder colours.
(function placeholder() {
  const c = canvas64();
  const g = c.getContext('2d');
  const fill = (x, y, w, h, col) => { g.fillStyle = col; g.fillRect(x, y, w, h); };
  fill(0, 0, 32, 16, '#c9a27e'); fill(8, 8, 8, 8, '#d8b08c');
  fill(16, 16, 24, 16, '#7a5fd0'); fill(40, 16, 14, 16, '#c9a27e');
  fill(0, 16, 16, 16, '#3d3a6b'); fill(16, 48, 16, 16, '#3d3a6b'); fill(32, 48, 14, 16, '#c9a27e');
  fill(9, 11, 2, 1, '#2a2340'); fill(13, 11, 2, 1, '#2a2340');
  showSkin(c);
})();

// Exposed for tests: the same functions the page uses.
window.__rigs = { toCanvas, looksSlim, toSlim, modelJson, buildPack, BONES };
