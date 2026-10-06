import * as L from './logic-model.mjs';
import * as A from './animation-model.mjs';
import * as C from './controls-model.mjs';
// Pure document operations. UiDocument children are the only hierarchy and painter order.
export const clone = value => JSON.parse(JSON.stringify(value));
export const fingerprint = value => JSON.stringify(value);
export const uid = (prefix = 'layer') => `${prefix}-${globalThis.crypto.randomUUID().slice(0, 8)}`;
export const anchors = ['top_left', 'top_right', 'center', 'bottom_left', 'bottom_right'];
export function flatten(doc) {
  const result = [];
  function walk(nodes, parent, depth, ox, oy, pw, ph, opacity) {
    nodes.forEach((node, index) => {
      const l = node.layout;
      const dx = l.anchor.endsWith('right') ? pw - l.width : l.anchor === 'center' ? (pw - l.width) / 2 : 0;
      const dy = l.anchor.startsWith('bottom') ? ph - l.height : l.anchor === 'center' ? (ph - l.height) / 2 : 0;
      const item = {node, parent, depth, index, x: ox + l.x + dx, y: oy + l.y + dy,
        width: l.width, height: l.height, opacity: opacity * node.style.opacity};
      result.push(item);
      walk(node.children ?? [], node.id, depth + 1, item.x, item.y, l.width, l.height, item.opacity);
    });
  }
  walk(doc.children, null, 0, 0, 0, doc.width, doc.height, 1);
  return result;
}
export const find = (doc, id) => flatten(doc).find(item => item.node.id === id);
export function roots(doc, ids) {
  const selected = new Set(ids), items = flatten(doc), byId = new Map(items.map(i => [i.node.id, i]));
  return items.filter(item => {
    if (!selected.has(item.node.id)) return false;
    let parent = item.parent;
    while (parent) { if (selected.has(parent)) return false; parent = byId.get(parent)?.parent; }
    return true;
  });
}
export function bounds(items) {
  if (!items.length) return null;
  const x = Math.min(...items.map(i => i.x)), y = Math.min(...items.map(i => i.y));
  return {x, y, width: Math.max(...items.map(i => i.x + i.width)) - x, height: Math.max(...items.map(i => i.y + i.height)) - y};
}
export function setWorld(doc, id, rect) {
  const item = find(doc, id); if (!item) return;
  const parent = item.parent ? find(doc, item.parent) : {x: 0, y: 0, width: doc.width, height: doc.height};
  const l = item.node.layout, w = Math.max(1, rect.width), h = Math.max(1, rect.height);
  const dx = l.anchor.endsWith('right') ? parent.width - w : l.anchor === 'center' ? (parent.width - w) / 2 : 0;
  const dy = l.anchor.startsWith('bottom') ? parent.height - h : l.anchor === 'center' ? (parent.height - h) / 2 : 0;
  Object.assign(l, {x: rect.x - parent.x - dx, y: rect.y - parent.y - dy, width: w, height: h});
}
export function translate(doc, ids, dx, dy) {
  roots(doc, ids).forEach(i => { i.node.layout.x += dx; i.node.layout.y += dy; });
}
export function resize(doc, ids, rect) {
  const items = roots(doc, ids), before = bounds(items); if (!before) return;
  const sx = Math.max(1, rect.width) / before.width, sy = Math.max(1, rect.height) / before.height;
  items.forEach(i => setWorld(doc, i.node.id, {x: rect.x + (i.x - before.x) * sx, y: rect.y + (i.y - before.y) * sy, width: i.width * sx, height: i.height * sy}));
}
export function remove(doc, ids, behaviors = true) {
  const removed = flatten(doc).filter(i=>ids.includes(i.node.id)||roots(doc,ids).some(r=>flatten({width:r.width,height:r.height,children:r.node.children??[]}).some(c=>c.node.id===i.node.id))).map(i=>i.node.id);
  const chosen = new Set(ids);
  const filter = nodes => nodes.filter(n => !chosen.has(n.id)).map(n => { if (n.children) n.children = filter(n.children); return n; });
  doc.children = filter(doc.children);
  if(behaviors && doc.logic)L.removeComponents(doc,removed);
  if(behaviors)C.remove(doc,removed);
}
export function parent(doc, ids, parentId = null, index = Infinity) {
  const items = roots(doc, ids), target = parentId ? find(doc, parentId) : null;
  if (parentId && (!target || target.node.type !== 'panel')) throw new Error('Choose a container or panel as the parent.');
  if (items.some(i => i.node.id === parentId || (i.node.children && flatten({width: i.width, height: i.height, children: i.node.children}).some(n => n.node.id === parentId)))) {
    throw new Error('A layer cannot contain itself or its parent.');
  }
  // An insertion index refers to the original sibling array, including layers being moved.
  const removedBefore = items.filter(i => i.parent === parentId && i.index < index).length;
  remove(doc, items.map(i => i.node.id), false);
  const list = parentId ? find(doc, parentId).node.children : doc.children;
  list.splice(Math.max(0, Math.min(list.length, index - removedBefore)), 0, ...items.map(i => i.node));
  items.forEach(i => setWorld(doc, i.node.id, i));
}
export function align(doc, ids, mode) {
  const items = roots(doc, ids), b = bounds(items); if (items.length < 2) return;
  items.forEach(i => {
    const rect = {...i};
    if (mode === 'left') rect.x = b.x;
    if (mode === 'hcenter') rect.x = b.x + (b.width - i.width) / 2;
    if (mode === 'right') rect.x = b.x + b.width - i.width;
    if (mode === 'top') rect.y = b.y;
    if (mode === 'vcenter') rect.y = b.y + (b.height - i.height) / 2;
    if (mode === 'bottom') rect.y = b.y + b.height - i.height;
    setWorld(doc, i.node.id, rect);
  });
}
export function distribute(doc, ids, axis) {
  const items = roots(doc, ids).sort((a, b) => a[axis] - b[axis] || a.node.id.localeCompare(b.node.id));
  if (items.length < 3) return;
  const size = axis === 'x' ? 'width' : 'height', end = items.at(-1)[axis] + items.at(-1)[size];
  const gap = (end - items[0][axis] - items.reduce((sum, i) => sum + i[size], 0)) / (items.length - 1);
  let position = items[0][axis];
  items.forEach(i => { setWorld(doc, i.node.id, {...i, [axis]: position}); position += i[size] + gap; });
}
export function snap(doc, ids, proposed, threshold = 6) {
  const excluded = new Set();
  roots(doc, ids).forEach(i => { excluded.add(i.node.id); flatten({width: i.width, height: i.height, children: i.node.children ?? []}).forEach(c => excluded.add(c.node.id)); });
  const targets = [{x: 0, y: 0, width: doc.width, height: doc.height}, ...flatten(doc).filter(i => !excluded.has(i.node.id) && i.opacity > 0)];
  const result = {dx: 0, dy: 0, guides: []};
  for (const axis of ['x', 'y']) {
    const size = axis === 'x' ? 'width' : 'height', values = [0, .5, 1];
    const choices = targets.flatMap(t => values.flatMap(a => values.map(b => ({delta: t[axis] + t[size] * a - (proposed[axis] + proposed[size] * b), at: t[axis] + t[size] * a}))));
    choices.sort((a, b) => Math.abs(a.delta) - Math.abs(b.delta) || a.at - b.at);
    if (choices.length && Math.abs(choices[0].delta) <= threshold) {
      result[axis === 'x' ? 'dx' : 'dy'] = choices[0].delta;
      result.guides.push({axis, at: choices[0].at});
    }
  }
  return result;
}
export function copy(doc, ids) {
  const result=roots(doc,ids).map(i=>({node:clone(i.node),x:i.x,y:i.y}));
  const components=new Set();const walk=nodes=>nodes.forEach(n=>{components.add(n.id);walk(n.children??[]);});walk(result.map(i=>i.node));
  const nodes=new Set(L.graph(doc).nodes.filter(n=>n.type.startsWith('on_')&&components.has(n.settings.component?.value)).map(n=>n.id));
  let changed;do{changed=false;for(const e of L.graph(doc).edges){if(nodes.has(e.from))changed=(!nodes.has(e.to)&&!!nodes.add(e.to))||changed;if(nodes.has(e.to)&&e.input!=='in')changed=(!nodes.has(e.from)&&!!nodes.add(e.from))||changed;}}while(changed);
  if(result.length)result[0].animations=A.copyComponents(doc,components);
  if(result.length)result[0].controls=clone(Object.fromEntries(Object.entries(doc.controls??{}).filter(([id])=>components.has(id))));
  if(result.length&&nodes.size)result[0].logic=L.copy(doc,[...nodes]);return result;
}
export function paste(doc, data, offset = 20) {
  const ids = [], componentMap = {};
  for (const entry of data) {
    const node = clone(entry.node);
    const renew = n => { const old=n.id; n.id = uid(n.type); componentMap[old]=n.id; n.children?.forEach(renew); }; renew(node);
    Object.assign(node.layout, {anchor: 'top_left', x: entry.x + offset, y: entry.y + offset});
    doc.children.push(node); ids.push(node.id);
  }
  const animations=A.pasteAnimations(doc,data[0]?.animations,componentMap);
  for(const [id,control] of Object.entries(data[0]?.controls??{}))if(componentMap[id])(doc.controls??={})[componentMap[id]]=clone(control);
  if(data[0]?.logic){const added=L.paste(doc,data[0].logic,offset,componentMap);for(const n of L.graph(doc).nodes)if(added.includes(n.id)&&animations[n.settings.animation?.value])n.settings.animation.value=animations[n.settings.animation.value];}
  return ids;
}
export function group(doc, ids) {
  const b = bounds(roots(doc, ids)); if (!b) return null;
  const node = component('container', b.x, b.y); node.layout.width = b.width; node.layout.height = b.height;
  doc.children.push(node); parent(doc, ids, node.id); return node.id;
}
export class History {
  constructor(doc) { this.doc = clone(doc); this.past = []; this.future = []; this.saved = fingerprint(doc); this.deployed = ''; }
  accept(before, label) {
    if (before === fingerprint(this.doc)) return false;
    this.past.push({value: before, label}); this.future = [];
    while (this.past.length > 100 || this.past.reduce((sum, e) => sum + e.value.length, 0) > 8_000_000) this.past.shift();
    return true;
  }
  execute(label, work) { const before = fingerprint(this.doc); const next = clone(this.doc); work(next); this.doc = next; return this.accept(before, label); }
  undo() { this.step(this.past, this.future); }
  redo() { this.step(this.future, this.past); }
  step(from, to) { if (!from.length) return; const entry = from.pop(); to.push({value: fingerprint(this.doc), label: entry.label}); this.doc = JSON.parse(entry.value); }
  get dirty() { return fingerprint(this.doc) !== this.saved; }
  get outdated() { return fingerprint(this.doc) !== this.deployed; }
}
export function assetUsage(doc) {
  const result = {}; flatten(doc).forEach(({node}) => { if (node.type === 'image') result[node.asset] = (result[node.asset] ?? 0) + 1; }); return result;
}
export function validation(doc, knownAssets = null) {
  const issues = [], ids = new Set();
  const issue = (id, code, message, level = 'error') => issues.push({componentId: id, code, message, level});
  if (!doc.name?.trim()) issue('', 'validation.name', 'Give this project a name.');
  for (const item of flatten(doc)) {
    const n = item.node, l = n.layout;
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(n.id) || ids.has(n.id)) issue(n.id, 'validation.component_id', 'Use a unique layer name with letters, numbers, hyphens or underscores.');
    ids.add(n.id);
    if ([l.x,l.y,l.width,l.height].some(v => !Number.isFinite(v)) || l.width <= 0 || l.height <= 0) issue(n.id, 'validation.layout', 'Enter a valid position and positive size.');
    if (item.x < 0 || item.y < 0 || item.x + item.width > doc.width || item.y + item.height > doc.height) issue(n.id, 'outside', 'Element extends outside the canvas.', 'warning');
    if (n.type === 'image' && (!/^[a-f0-9]{64}$/.test(n.asset) || knownAssets && !knownAssets.has(n.asset))) issue(n.id, 'editor.error.asset_missing', 'Image asset is missing.');
    if (n.type === 'button' && !doc.controls?.[n.id] && !L.entry(doc,n.id) && n.action.type !== 'close_ui' && !n.action.value.trim()) issue(n.id, 'empty_action', 'Button has no message or command.', 'warning');
  }
  return issues;
}
export const rgb = hex => hex.match(/[a-f0-9]{2}/gi).map(v => parseInt(v, 16));
export const hex = values => '#' + values.map(v => Math.max(0, Math.min(255, Math.round(Number(v) || 0))).toString(16).padStart(2, '0')).join('').toUpperCase();
export function component(kind, x = 60, y = 60, asset = '') {
  const type = ['container', 'spacer', 'progress','form','scroll'].includes(kind) ? 'panel' : C.kinds.includes(kind) ? 'button' : kind;
  const node = {id: uid(kind), type, layout: {x, y, width: type === 'text' ? 300 : 240, height: type === 'panel' ? 180 : 56, anchor: 'top_left'},
    style: {background: '#25364C', color: '#FFFFFF', opacity: kind === 'spacer' ? 0 : 1, fontSize: 22}};
  if (type === 'panel') node.children = [];
  if (type === 'text') node.text = 'Your next adventure';
  if (type === 'button') { node.text = 'Continue'; node.style.background = '#7965E9'; node.action = {type: 'send_message', value: 'Welcome!'}; }
  if(C.kinds.includes(kind)&&type==='button'){node.text={input:'Text input',checkbox:'Accept terms',toggle:'Notifications',slider:'Volume',select:'Choose an option'}[kind];node.action.value='';}
  if (type === 'image') { node.asset = asset; node.layout.height = 160; }
  if (kind === 'progress') {
    node.layout.height = 24; const fill = component('panel', 0, 0); fill.id = uid('fill'); fill.layout.width = 144; fill.layout.height = 24; fill.style.background = '#52CEAA'; node.children = [fill];
  }
  if (kind === 'spacer') { node.layout.width = 32; node.layout.height = 32; }
  return node;
}
export function template(kind, name = 'Untitled project') {
  const doc = {schemaVersion: 1, id: uid('project'), name, width: 900, height: 600, children: []};
  if (kind === 'blank') return doc;
  if (kind === 'hud') {
    const bar = component('progress', 32, 520); bar.layout.width = 320; bar.children[0].layout.width = 224;
    const text = component('text', 32, 478); text.text = 'Adventure level 7'; doc.children = [text, bar]; return doc;
  }
  const panel = component('panel', 0, 0); Object.assign(panel.layout, {anchor: 'center', width: 580, height: kind === 'confirm' ? 320 : 440});
  const title = component('text', 36, 34); title.text = kind === 'confirm' ? 'Ready to continue?' : 'Welcome, adventurer'; title.layout.width = 510; title.style.fontSize = 32;
  const detail = component('text', 36, 110); detail.text = kind === 'confirm' ? 'Choose an option below.' : 'A new chapter starts here.\nChoose your next destination.'; detail.layout.width = 510; detail.layout.height = 94; detail.style.color = '#B9C8DC';
  const button = component('button', 36, kind === 'confirm' ? 210 : 270); button.text = kind === 'confirm' ? 'Confirm' : 'Start exploring';
  const close = component('button', 300, button.layout.y); close.text = 'Close'; close.style.background = '#35475E'; close.action = {type: 'close_ui', value: ''};
  panel.children = [title, detail, button, close]; doc.children = [panel];
  if(kind==='confirm') {
    button.text='Yes';close.text='No';button.action.value='Confirmed!';
    const entry=L.editClick(doc,button),g=L.graph(doc),message=g.edges.find(e=>e.from===entry).to;
    const end={id:uid('node'),type:'close_ui',x:700,y:80,settings:{}};g.nodes.push(end);g.edges.push({from:message,output:'next',to:end.id,input:'in'});
    L.editClick(doc,close);const no=L.entry(doc,close.id);no.y=310;g.nodes.find(n=>n.id===g.edges.find(e=>e.from===no.id).to).y=310;
  }
  return doc;
}

export function rename(doc,oldId,id){find(doc,oldId).node.id=id;L.renameComponent(doc,oldId,id);A.renameComponent(doc,oldId,id);C.rename(doc,oldId,id);}
