// Pure graph operations. The server supplies the authoritative node/port catalog.
import {valueType} from './controls-model.mjs';
let catalog = {};
const clone = value => structuredClone(value);
const uid = () => 'node-' + globalThis.crypto.randomUUID().slice(0, 12);
export const labels = {on_animation_complete:'On animation complete',play_animation:'Play animation',pause_animation:'Pause animation',resume_animation:'Resume animation',stop_animation:'Stop animation',restart_animation:'Restart animation',on_open:'On UI open',on_close:'On UI close',on_click:'On button click',on_hover_enter:'On hover enter',on_hover_exit:'On hover exit',has_permission:'Has permission',placeholder_equals:'Placeholder equals',placeholder_contains:'Placeholder contains',placeholder_numeric:'Compare placeholder number',is_sneaking:'Player is sneaking',is_flying:'Player is flying',branch:'Branch / If',sequence:'Sequence',delay:'Delay',stop:'Stop',send_message:'Send message',player_command:'Run player command',close_ui:'Close UI',open_ui:'Open UI',set_variable:'Set variable',add_number:'Add number',toggle_boolean:'Toggle boolean',set_visible:'Set visible',set_text:'Set text',set_opacity:'Set opacity',constant_string:'String',constant_number:'Number',constant_boolean:'Boolean',variable:'Session variable',placeholder:'Placeholder value',player_name:'Player name'};
export const value = (type, raw = type==='BOOLEAN'?false:type==='NUMBER'?0:'') => ({type,value:String(raw)});
export function configure(entries) { catalog = clone(entries); }
export function entries() { return Object.entries(catalog).sort(([a],[b])=>a.localeCompare(b)); }
export function graph(doc) { return doc.logic ?? {schemaVersion:1,nodes:[],edges:[],variables:[]}; }
export function ensure(doc) { return doc.logic ??= {schemaVersion:1,nodes:[],edges:[],variables:[]}; }
export function spec(node, doc) {
  const result = clone(catalog[node?.type] ?? {category:'unknown',inputs:{},outputs:{},settings:{}});
  const type = graph(doc).variables.find(v=>v.name===node?.settings?.variable?.value)?.type ?? 'STRING';
  if(node?.type==='variable') result.outputs.value=type;
  if(node?.type==='set_variable') result.inputs.value=type;
  if(node?.type==='set_value') result.inputs.value=node.settings?.valueType?.value??'STRING';
  return result;
}
function components(doc) { const result=[]; const walk=nodes=>nodes.forEach(n=>{result.push(n);walk(n.children??[]);});walk(doc.children);return result; }
export function acceptsComponent(doc,type,node){
  const c=doc.controls?.[node.id],kind=c?.kind,field=c&&!['FORM','SCROLL'].includes(kind);
  const targets={input_value:'INPUT',clear_input:'INPUT',checkbox_value:'CHECKBOX',toggle_value:'TOGGLE',slider_value:'SLIDER',selected_value:'SELECT',form_valid:'FORM',reset_form:'FORM',submit_form:'FORM',on_submit:'FORM',scroll_position:'SCROLL',scroll_to:'SCROLL',on_scroll:'SCROLL'};
  if(targets[type])return kind===targets[type];
  if(['set_value','focus_component','blur_component','on_focus','on_blur','on_value_change'].includes(type))return !!field;
  if(['set_disabled','validation_message'].includes(type))return !!c;
  if(['on_click','on_hover_enter','on_hover_exit'].includes(type))return node.type==='button';
  return type!=='set_text'||['text','button'].includes(node.type);
}
export function controlTarget(doc,node){
  if(node.type!=='set_value')return;
  const c=doc.controls?.[node.settings.component?.value],type=c?valueType(c):'STRING';
  node.settings.valueType=value('STRING',type);node.settings.value=value(type);
  const g=graph(doc);g.edges=g.edges.filter(e=>e.to!==node.id||e.input!=='value');
}
export function create(doc,type,x=80,y=80) {
  if(!catalog[type]) throw new Error('Unknown node type.');
  if(graph(doc).nodes.length>=128) throw new Error('A graph can contain up to 128 nodes.');
  const node={id:uid(),type,x,y,settings:{}}, s=spec(node,doc);
  for(const [key,kind] of Object.entries(s.settings)) {
    let initial=kind==='NUMBER'?type==='delay'?20:0:kind==='BOOLEAN'?false:'';
    if(key==='component')initial=components(doc).find(n=>acceptsComponent(doc,type,n))?.id??'';
    if(key==='animation')initial=doc.animations?.items?.[0]?.id??'';
    if(key==='project')initial=doc.id;
    if(key==='permission')initial='wellui.use';
    if(key==='placeholder')initial='%player_name%';
    if(key==='operator')initial='ge';
    if(key==='variable')initial=graph(doc).variables.find(v=>type==='add_number'?v.type==='NUMBER':type==='toggle_boolean'?v.type==='BOOLEAN':true)?.name??'';
    node.settings[key]=value(kind,initial);
  }
  controlTarget(doc,node);
  for(const [port,kind] of Object.entries(spec(node,doc).inputs)) if(kind!=='EXECUTION')node.settings[port]=value(kind,type==='set_opacity'?1:undefined);
  ensure(doc).nodes.push(node); return node;
}
export function canConnect(doc, from, output, to, input) {
  const g=graph(doc), a=g.nodes.find(n=>n.id===from), b=g.nodes.find(n=>n.id===to);
  if(!a||!b||a===b)return false;
  const type=spec(a,doc).outputs[output];if(!type||type!==spec(b,doc).inputs[input])return false;
  const seen=new Set();const reaches=id=>{if(id===from)return true;if(seen.has(id))return false;seen.add(id);return g.edges.filter(e=>e.from===id).some(e=>reaches(e.to));};
  return !reaches(to);
}
export function connect(doc,from,output,to,input) {
  if(!canConnect(doc,from,output,to,input))throw new Error('Choose a matching port without creating a loop.');
  const g=ensure(doc), type=spec(g.nodes.find(n=>n.id===from),doc).outputs[output];
  g.edges=g.edges.filter(e=>!(e.from===from&&e.output===output&&type==='EXECUTION')&&!(e.to===to&&e.input===input&&type!=='EXECUTION'));
  if(g.edges.length>=256)throw new Error('A graph can contain up to 256 connections.');
  g.edges.push({from,output,to,input});
}
export function remove(doc,ids) { const chosen=new Set(ids),g=ensure(doc);g.nodes=g.nodes.filter(n=>!chosen.has(n.id));g.edges=g.edges.filter(e=>!chosen.has(e.from)&&!chosen.has(e.to)); }
export function copy(doc,ids) { const chosen=new Set(ids),g=graph(doc);return clone({nodes:g.nodes.filter(n=>chosen.has(n.id)),edges:g.edges.filter(e=>chosen.has(e.from)&&chosen.has(e.to)),variables:g.variables}); }
export function paste(doc,data,offset=32,componentMap={}) {
  if(!data?.nodes?.length)return[]; const g=ensure(doc);if(g.nodes.length+data.nodes.length>128)throw new Error('A graph can contain up to 128 nodes.');
  if(g.edges.length+(data.edges??[]).length>256)throw new Error('A graph can contain up to 256 connections.');
  const names=new Map();
  for(const variable of data.variables??[]) {
    let name=variable.name;const existing=g.variables.find(v=>v.name===name);
    if(existing&&existing.type!==variable.type){let i=1;while(g.variables.some(v=>v.name===name))name=variable.name.slice(0,54)+'_copy'+i++;}
    if(!g.variables.some(v=>v.name===name)){if(g.variables.length>=32)throw new Error('A graph can contain up to 32 variables.');g.variables.push({...clone(variable),name});}
    names.set(variable.name,name);
  }
  const ids=new Map(data.nodes.map(n=>[n.id,uid()]));
  g.nodes.push(...data.nodes.map(n=>{const node=clone(n);node.id=ids.get(n.id);node.x+=offset;node.y+=offset;
    if(node.settings.component&&componentMap[node.settings.component.value])node.settings.component.value=componentMap[node.settings.component.value];
    if(node.settings.variable&&names.has(node.settings.variable.value))node.settings.variable.value=names.get(node.settings.variable.value);return node;}));
  g.edges.push(...data.edges.filter(e=>ids.has(e.from)&&ids.has(e.to)).map(e=>({...e,from:ids.get(e.from),to:ids.get(e.to)})));
  return [...ids.values()];
}
export function entry(doc,component) { return graph(doc).nodes.find(n=>n.type==='on_click'&&n.settings.component?.value===component); }
export function editClick(doc,button) {
  if(doc.controls?.[button.id]) {
    const type=doc.controls[button.id].kind==='FORM'?'on_submit':doc.controls[button.id].kind==='SCROLL'?'on_scroll':'on_value_change';
    const found=graph(doc).nodes.find(n=>n.type===type&&n.settings.component?.value===button.id);if(found)return found.id;
    const g=ensure(doc);if(g.nodes.length>=128)throw new Error('Make room for the control event.');
    const event={id:uid(),type,x:80,y:80+g.nodes.length*12,settings:{component:value('STRING',button.id)}};
    g.nodes.push(event);return event.id;
  }
  const previous=entry(doc,button.id);if(previous)return previous.id;
  const g=ensure(doc);if(g.edges.length>=256)throw new Error('Make room for the button connection.');if(g.nodes.length>126)throw new Error('Make room for the button event and action.');
  const event={id:uid(),type:'on_click',x:80,y:80+g.nodes.length*12,settings:{component:value('STRING',button.id)}};
  const action={id:uid(),type:button.action.type,x:390,y:event.y,settings:button.action.type==='close_ui'?{}:{value:value('STRING',button.action.value)}};
  g.nodes.push(event,action);g.edges.push({from:event.id,output:'next',to:action.id,input:'in'});return event.id;
}
export function simple(doc,button) {
  const event=entry(doc,button.id);if(!event)return {...button.action,legacy:true};
  const g=graph(doc),edges=g.edges.filter(e=>e.from===event.id);if(edges.length!==1)return null;
  const action=g.nodes.find(n=>n.id===edges[0].to);
  if(!action||!['send_message','player_command','close_ui'].includes(action.type)||g.edges.some(e=>e.from===action.id||e.to===action.id&&e.input!=='in'))return null;
  return {type:action.type,value:action.settings.value?.value??'',node:action.id};
}
export function setSimple(doc,button,type,text) {
  const current=simple(doc,button);if(!current)throw new Error('Edit this behavior in Logic mode.');
  if(current.node){const n=graph(doc).nodes.find(n=>n.id===current.node);n.type=type;n.settings=type==='close_ui'?{}:{value:value('STRING',text)};}
  button.action={type,value:type==='close_ui'?'':text};
}
export function renameComponent(doc,oldId,id) { graph(doc).nodes.forEach(n=>{if(n.settings.component?.value===oldId)n.settings.component.value=id;}); }
export function removeComponents(doc,ids) { remove(doc,graph(doc).nodes.filter(n=>ids.includes(n.settings.component?.value)).map(n=>n.id)); }
export function duplicateProject(doc,id) { const copy=clone(doc),old=copy.id;copy.id=id;copy.name+=' copy';graph(copy).nodes.forEach(n=>{if(n.type==='open_ui'&&n.settings.project?.value===old)n.settings.project.value=id;});return copy; }
export function validation(doc) {
  const g=graph(doc),issues=[],ids=new Set(),events=new Set(),componentIds=new Set(components(doc).map(n=>n.id));
  const issue=(nodeId,message,level='error')=>issues.push({nodeId,code:'logic.browser',message,level});
  if(g.nodes.length>128||g.edges.length>256||g.variables.length>32)issue('','This graph exceeds its node, connection or variable limit.');
  for(const n of g.nodes){if(ids.has(n.id))issue(n.id,'Node IDs must be unique.');ids.add(n.id);
    if(!catalog[n.type])issue(n.id,'Unknown node type.');
    if(n.settings.component&&!componentIds.has(n.settings.component.value))issue(n.id,'Choose an existing component.');
    if(n.type.startsWith('on_')){const key=n.type+':'+(n.settings.animation?.value??n.settings.component?.value??'');if(events.has(key))issue(n.id,'This event already has a starting node.');events.add(key);}
    if(n.settings.variable&&!g.variables.some(v=>v.name===n.settings.variable.value))issue(n.id,'Choose a session variable.');
    if(n.type==='has_permission'&&!/^[a-zA-Z0-9_*][a-zA-Z0-9_.:*\-]{0,127}$/.test(n.settings.permission?.value??''))issue(n.id,'Enter a valid permission.');
    if(n.type==='delay'&&(!Number.isInteger(Number(n.settings.ticks?.value))||Number(n.settings.ticks.value)<1||Number(n.settings.ticks.value)>1200))issue(n.id,'Delay must be 1–1200 ticks.');
    if(n.type==='set_opacity'&&n.settings.value&&(Number(n.settings.value.value)<0||Number(n.settings.value.value)>1))issue(n.id,'Opacity must be between 0 and 1.');
    if(n.type==='player_command'&&n.settings.value&&(!n.settings.value.value.trim()||n.settings.value.value.startsWith('/')))issue(n.id,'Enter a player command without a leading /.');
    for(const [port,type] of Object.entries(spec(n,doc).inputs))if(type!=='EXECUTION'&&!g.edges.some(e=>e.to===n.id&&e.input===port)&&!n.settings[port])issue(n.id,'Connect or enter '+port+'.');
    if(n.type.startsWith('on_')&&!g.edges.some(e=>e.from===n.id))issue(n.id,'This event has no connected behavior.','warning');
  }
  for(const e of g.edges){const a=g.nodes.find(n=>n.id===e.from),b=g.nodes.find(n=>n.id===e.to);if(!a||!b||!spec(a,doc).outputs[e.output]||spec(a,doc).outputs[e.output]!==spec(b,doc).inputs[e.input])issue(e.to,'Connection has a missing node or incompatible port.');}
  const active=new Set(),done=new Set(),visit=id=>{if(active.has(id)){issue(id,'A connection creates a cycle.');return;}if(done.has(id))return;active.add(id);g.edges.filter(e=>e.from===id).forEach(e=>visit(e.to));active.delete(id);done.add(id);};g.nodes.forEach(n=>visit(n.id));
  const reached=new Set(),reach=id=>{if(reached.has(id))return;reached.add(id);g.edges.filter(e=>e.from===id&&spec(g.nodes.find(n=>n.id===id),doc).outputs[e.output]==='EXECUTION').forEach(e=>reach(e.to));};g.nodes.filter(n=>n.type.startsWith('on_')).forEach(n=>reach(n.id));
  let changed;do{changed=false;for(const e of g.edges)if(e.input!=='in'&&reached.has(e.to)&&!reached.has(e.from)){reached.add(e.from);changed=true;}}while(changed);
  g.nodes.filter(n=>!reached.has(n.id)).forEach(n=>issue(n.id,'This node is not connected to an event.','warning'));
  return issues;
}
