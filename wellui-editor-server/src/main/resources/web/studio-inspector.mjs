import * as L from './logic-model.mjs';
import * as M from './studio-model.mjs';
import {controlInspector} from './controls-inspector.mjs';
const el = (tag, cls, text) => { const e = document.createElement(tag); e.className = cls ?? ''; if (text !== undefined) e.textContent = text; return e; };
export function inspector(root, {doc, ids, locked, change, select, replaceImage, duplicate, remove, editLogic}) {
  root.replaceChildren();
  const items = M.roots(doc, ids), first = items[0]?.node;
  const section = title => { const s = el('section', 'inspector-section'); s.append(el('h3', '', title)); root.append(s); return s; };
  const field = (container, title, value, callback, opts = {}) => {
    const label = el('label', 'field', title), input = el(opts.options ? 'select' : opts.multiline ? 'textarea' : 'input');
    if (opts.options) opts.options.forEach(v => { const o = el('option', '', typeof v === 'string' ? v.replaceAll('_', ' ') : v.label); o.value = typeof v === 'string' ? v : v.value; input.append(o); });
    else if (!opts.multiline) input.type = opts.number ? 'number' : 'text';
    input.value = value; input.setAttribute('aria-label', title); input.disabled = opts.disabled ?? false;
    if (opts.number) { input.step = opts.step ?? 'any'; if (opts.min !== undefined) input.min = opts.min; if (opts.max !== undefined) input.max = opts.max; }
    input.onchange = () => { const next = opts.number ? Number(input.value) : input.value; if (opts.number && (!Number.isFinite(next) || opts.min !== undefined && next < opts.min || opts.max !== undefined && next > opts.max)) { input.value = value; return; } callback(next); };
    label.append(input); container.append(label); return input;
  };
  const button = (container, title, action, disabled = false) => { const b = el('button', '', title); b.onclick = action; b.disabled = disabled; container.append(b); return b; };
  if (!first) {
    const s = section('Canvas');
    field(s, 'Project name', doc.name, value => change('Rename project', d => { d.name = value; }));
    const grid = el('div', 'field-grid'); s.append(grid);
    for (const key of ['width', 'height']) field(grid, key === 'width' ? 'Width' : 'Height', doc[key], value => change('Resize canvas', d => { d[key] = value; }), {number: true, min: 1, max: 16384});
    s.append(el('p', 'hint', 'Logical design units. Preview presets do not change your project dimensions.'));
    const note = section('Make it yours'); note.append(el('p', '', 'Add components from the left, then select a layer to edit its layout, appearance and behavior.'));
    return;
  }
  const allLocked = items.some(i => locked.has(i.node.id));
  const update = (label, work) => change(label, d => items.forEach(i => work(M.find(d, i.node.id).node)), !allLocked);
  const typeName=doc.controls?.[first.id]?.kind.toLowerCase()??first.type;
  const head = section(items.length === 1 ? typeName[0].toUpperCase() + typeName.slice(1) : `${items.length} selected layers`);
  if (items.length === 1) field(head, 'Layer name', first.id, value => {
    const id = value.trim().toLowerCase().replaceAll(' ', '-');
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(id) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/.test(id) || M.find(doc, id) && id !== first.id) return;
    change('Rename layer',d=>M.rename(d,first.id,id),!allLocked); select([id]);
  }, {disabled: allLocked});
  const actions = el('div', 'row'); head.append(actions); button(actions, 'Duplicate', duplicate); button(actions, 'Delete', remove, allLocked);
  if (allLocked) head.append(el('p', 'hint', 'Unlock the selected layer in Layers to edit it.'));
  const layout = section('Layout'), grid = el('div', 'field-grid'); layout.append(grid);
  const box = items.length === 1 ? first.layout : M.bounds(items);
  for (const key of ['x','y','width','height']) field(grid, {x:'X',y:'Y',width:'Width',height:'Height'}[key], Math.round(box[key] * 100) / 100, value => {
    if (items.length === 1) update('Change layout', n => { n.layout[key] = value; });
    else change('Transform selection', d => { const b = M.bounds(M.roots(d, ids)); if (key === 'x' || key === 'y') M.translate(d, ids, key === 'x' ? value - b.x : 0, key === 'y' ? value - b.y : 0); else M.resize(d, ids, {...b, [key]: value}); }, !allLocked);
  }, {number:true,min: key === 'width' || key === 'height' ? 1 : -16384,max:16384,disabled:allLocked});
  // Changing an anchor must retain the visible position, not teleport to a different canvas corner.
  if (items.length === 1) {
    field(layout, 'Anchor', first.layout.anchor, value => change('Change anchor', d => {
      const before = M.find(d, first.id); before.node.layout.anchor = value; M.setWorld(d, first.id, before);
    }), {options:M.anchors,disabled:allLocked});
    layout.append(el('p','hint','X and Y are offsets from the chosen parent anchor. Changing the anchor keeps the layer in place. Positive offsets move right and down.'));
    const options = [{value:'',label:'Canvas'}, ...M.flatten(doc).filter(i => i.node.type === 'panel' && i.node.id !== first.id).map(i => ({value:i.node.id,label:i.node.id}))];
    field(layout, 'Parent', items[0].parent ?? '', value => change('Reparent layer', d => M.parent(d, ids, value || null)), {options,disabled:allLocked});
  }
  const align = el('div', 'alignment'); layout.append(align);
  for (const [key, label] of Object.entries({left:'Align left',hcenter:'Center X',right:'Align right',top:'Align top',vcenter:'Center Y',bottom:'Align bottom'})) button(align,label,() => change('Align layers',d => M.align(d,ids,key)),items.length<2 || allLocked);
  button(align,'Distribute X',() => change('Distribute layers',d => M.distribute(d,ids,'x')),items.length<3 || allLocked);
  button(align,'Distribute Y',() => change('Distribute layers',d => M.distribute(d,ids,'y')),items.length<3 || allLocked);
  const appearance = section('Appearance');
  const color = (title, key) => {
    const label = el('label','field',title), row = el('div','color-row'), picker = el('input'), text = el('input');
    picker.type='color'; picker.value=first.style[key]; picker.setAttribute('aria-label',title+' picker'); text.value=first.style[key].toUpperCase(); text.setAttribute('aria-label',title+' HEX');
    const apply = value => { if (/^#[a-f0-9]{6}$/i.test(value)) update('Change color',n => { n.style[key]=value.toUpperCase(); }); };
    picker.onchange=() => apply(picker.value); text.onchange=() => apply(text.value); picker.disabled=text.disabled=allLocked;
    row.append(picker,text); label.append(row); const rgbRow=el('div','rgb-row'), values=M.rgb(first.style[key]);
    values.forEach((value,index) => { const input=el('input'); input.type='number'; input.min=0; input.max=255; input.value=value; input.setAttribute('aria-label',title+' '+['Red','Green','Blue'][index]); input.disabled=allLocked; input.onchange=() => { values[index]=Number(input.value); apply(M.hex(values)); }; rgbRow.append(input); });
    label.append(rgbRow); appearance.append(label);
  };
  if (!['text','image'].includes(first.type) || items.length>1) color('Background','background');
  field(appearance,'Opacity / alpha',first.style.opacity,value => update('Change opacity',n => { n.style.opacity=value; }),{number:true,min:0,max:1,step:.05,disabled:allLocked});
  appearance.append(el('p','hint','Borders and corner radius are not supported by the current document format.'));
  if (items.length===1 && ['text','button'].includes(first.type)) {
    const text=section('Typography'); field(text,'Text content',first.text,value => update('Edit text',n => { n.text=value; }),{multiline:true,disabled:allLocked});
    field(text,'Font size',first.style.fontSize,value => update('Change font size',n => { n.style.fontSize=Math.round(value); }),{number:true,min:1,max:256,step:1,disabled:allLocked});
    // Color shares the same normalised Style field across text and buttons.
    color('Text color','color'); text.append(el('p','hint',`Sans-serif · ${first.type==='button'?'Bold':'Regular'} · Top-left aligned. Font selection and custom alignment are not supported yet.`));
  }
  if (items.length===1 && first.type==='image') { const image=section('Image'); button(image,'Replace image',replaceImage,allLocked); image.append(el('p','hint','Contain fit · interpolation follows server image settings. Transparent pixels are preserved.')); }
  if(items.length===1)controlInspector({doc,id:first.id,section,field,change,disabled:allLocked});
  if(items.length===1&&doc.controls?.[first.id]&&first.type==='button')button(section('Behavior'),'Edit Logic',()=>editLogic(first.id),allLocked);
  if (items.length===1 && first.type==='button' && !doc.controls?.[first.id]) {
    const interaction=section('Interaction');
    const action=L.simple(doc,first);
    if(action){
      field(interaction,'Click action',action.type,value=>change('Change action',d=>L.setSimple(d,M.find(d,first.id).node,value,value==='close_ui'?'':action.value),!allLocked),{options:[{value:'send_message',label:'Send a message'},{value:'close_ui',label:'Close interface'},{value:'player_command',label:'Run player command'}],disabled:allLocked});
      if(action.type!=='close_ui')field(interaction,action.type==='send_message'?'Message':'Command (without /)',action.value,value=>change('Edit action',d=>L.setSimple(d,M.find(d,first.id).node,action.type,value),!allLocked),{multiline:true,disabled:allLocked});
    } else interaction.append(el('p','hint','This button uses a logic graph. Edit its connected nodes to change the behavior.'));
    button(interaction,'Edit Logic',()=>editLogic(first.id),allLocked);
    interaction.append(el('p','hint','Hover highlight is automatic. Visible buttons are enabled. Commands run with the clicking player’s permissions.'));
  }
  const visibility=section('Visibility');
  const check=el('input'); check.type='checkbox'; check.checked=first.style.opacity>0; check.disabled=allLocked; check.onchange=() => update('Toggle visibility',n => { n.style.opacity=check.checked?1:0; });
  const label=el('label','row'); label.append(check,el('span','','Visible in the interface')); visibility.append(label);
}
