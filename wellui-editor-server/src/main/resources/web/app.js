import * as M from './studio-model.mjs';
import * as C from './controls-model.mjs';
import {collect,chooseDraft,acceptRename} from './deployment-model.mjs';
import {paint,clippedBounds} from './controls-preview.mjs';
import {inspector} from './studio-inspector.mjs';
import * as L from './logic-model.mjs';
import {LogicEditor} from './logic-editor.mjs';
import * as A from './animation-model.mjs';
import {AnimationEditor} from './animation-editor.mjs';

const $ = id => document.getElementById(id);
const el = (tag, cls = '', text) => { const node = document.createElement(tag); node.className = cls; if (text !== undefined) node.textContent = text; return node; };
const dictionary = await fetch('/en_US.json').then(r => r.json());
const t = key => dictionary[key] ?? key;
let token = new URLSearchParams(location.hash.slice(1)).get('token') ?? sessionStorage.getItem('wellui-token') ?? '';
history.replaceState(null, '', location.pathname);
let mode='design';
let previewState='NORMAL';
let state = null, selection = [], tab = 'components', busy = false, preview = false, space = false;
let zoom = .65, pan = {x:50,y:40}, pointer = null, clipboard = [], guides = [], marquee = null;
let projects = [], assets = [], serverStatus = {}, revision = '', serverIssues = [], maxAssetBytes = 4*1024*1024;
let uploadTarget = null, draftTimer, statusBusy = false;
const locked = new Set(), urls = new Map(), loadingImages = new Set(), failedImages = new Set();
const doc = () => state?.doc;
const editable = () => M.roots(doc(), selection).filter(i => !isLocked(i.node.id)).map(i => i.node.id);
const icons = {container:'▣',panel:'▤',text:'T',image:'▧',button:'↗',progress:'▰',spacer:'↔'};
const palette = [['container','Container'],['panel','Panel'],['text','Text'],['image','Image'],['button','Button'],['progress','Progress bar'],['spacer','Spacer']];
palette.push(['input','Text input'],['checkbox','Checkbox'],['toggle','Toggle'],['slider','Slider'],['select','Select'],['form','Form'],['scroll','Scroll container']);
Object.assign(icons,{input:'⌨',checkbox:'☑',toggle:'◉',slider:'━',select:'▾',form:'▤',scroll:'↕'});
const logic=new LogicEditor({root:$('logic-viewport'),palette:$('logic-palette'),inspector:$('inspector'),document:doc,change,report,projects:()=>projects,issues:()=>serverIssues});
const animate=new AnimationEditor({root:$('animation-viewport'),palette:$('animation-palette'),inspector:$('inspector'),document:doc,change,report,askName:nameDialog,confirm:confirmDialog,asset:id=>{if(urls.has(id))return urls.get(id);if(!failedImages.has(id)&&!loadingImages.has(id))imageUrl(id).then(()=>{if(mode==='animate')animate.preview();});return '';}});
function setMode(next){animate.pause();mode=next;$('canvas-controls-hint').textContent=mode==='logic'?'Drag background to pan · Shift-select nodes · Connect matching ports':'Space + drag to pan · Ctrl + drag for marquee · Shift to add selection · Alt to bypass snapping';preview=false;$('workspace').classList.remove('previewing');$('preview').textContent='Preview';$('workspace').classList.toggle('logic-mode',mode==='logic');
  $('workspace').classList.toggle('animate-mode',mode==='animate');$('animation-viewport').hidden=mode!=='animate';$('animation-palette').hidden=mode!=='animate';$('animate-mode').classList.toggle('active',mode==='animate');$('viewport').hidden=mode!=='design';$('logic-viewport').hidden=mode!=='logic';$('logic-palette').hidden=mode!=='logic';$('preview').disabled=mode!=='design';$('viewport-preset').hidden=mode!=='design';
  $('inspector-mode').textContent=mode.toUpperCase();$('design-mode').classList.toggle('active',mode==='design');$('logic-mode').classList.toggle('active',mode==='logic');render();if(mode==='logic')logic.fit();}
function editLogic(id){let node;change('Edit button logic',d=>{node=L.editClick(d,M.find(d,id).node);});if(node){setMode('logic');logic.focus(node);}}
$('design-mode').onclick=()=>setMode('design');$('logic-mode').onclick=()=>setMode('logic');$('animate-mode').onclick=()=>setMode('animate');
function isLocked(id) { let i = M.find(doc(),id); while(i) { if(locked.has(i.node.id)) return true; i=i.parent?M.find(doc(),i.parent):null; } return false; }
function report(message, kind='') { $('feedback').textContent=message; $('feedback').className=kind; }
async function api(path, method='GET', body=null, name='') {
  const headers={Authorization:`Bearer ${token}`};
  if(body!==null) headers['Content-Type']=body instanceof Blob?'image/png':'application/json';
  if(name) headers['X-Asset-Name']=encodeURIComponent(name);
  const response=await fetch('/api/v2'+path,{method,headers,body:body===null?null:body instanceof Blob?body:JSON.stringify(body),signal:AbortSignal.timeout(60000)});
  if(response.status===401 && !$('auth-dialog').open) $('auth-dialog').showModal();
  if(!response.ok) { const error=await response.json().catch(()=>({})); const failure=new Error(error.messages?.join('\n')||t(error.error??'error_network')); failure.status=response.status; failure.issues=error.issues??[]; throw failure; }
  return response.headers.get('Content-Type')?.startsWith('image/')?response.blob():response.json();
}
async function run(work) {
  if(busy) return; busy=true; controls();
  try { await work(); } catch(error) { if(error.issues?.length) serverIssues=error.issues; report(error.message,'error'); renderIssues(); }
  finally { busy=false; controls(); }
}
function button(label, work, cls='') { const b=el('button',cls,label); b.onclick=work; return b; }
function change(label, work, permitted=true) {
  if(!state || !permitted || preview) return;
  try { state.execute(label,work); serverIssues=[]; selection=selection.filter(id=>M.find(doc(),id)); render(); queueDraft(); }
  catch(error) { report(error.message,'error'); }
}
function select(ids) { selection=[...new Set(ids)].filter(id=>M.find(doc(),id)); render(); }
function controls() {
  for(const id of ['save','deploy','validate','new-project','starter']) $(id).disabled=busy || !state;
  $('undo').disabled=!state?.past.length || preview;
  $('redo').disabled=!state?.future.length || preview;
  $('save-state').textContent=busy?'Working…':state?.dirty?'Unsaved changes':'Saved';
  const local=serverStatus.runtime?.connected===false;
  $('deploy-state').textContent=!state?'No project':state.outdated ? state.deployed?(local?'Export outdated':'Deployment outdated'):(local?'Not exported':'Not deployed'):(local?'Exported locally':'Deployed');
}
function queueDraft() {
  clearTimeout(draftTimer); draftTimer=setTimeout(persistDraft,700);
}
function persistDraft() {
  if(!state) return;
  try { localStorage.setItem('wellui-draft:'+doc().id,JSON.stringify({document:doc(),saved:state.saved,locked:[...locked],at:Date.now()})); }
  catch { report('Local recovery storage is full. Save your project to keep these changes.','error'); }
}
async function refreshProjects() { projects=await api('/projects'); renderProjects(); }
async function openProject(id, force=false) {
  if(state?.dirty && !force && !await confirmDialog('Leave this project? A local recovery draft will be kept.')) return;
  animate.pause();persistDraft(); const document=await api('/projects/'+id); state=new M.History(document); selection=[]; logic.ids=[];logic.edge=null;logic.wire=null; locked.clear(); serverIssues=[];
  const entry=projects.find(p=>p.id===id); revision=entry?.revision??'';
  if(entry?.deployedRevision && entry.deployedRevision===entry.revision) state.deployed=state.saved;
  try {
    const recovery=JSON.parse(localStorage.getItem('wellui-draft:'+id)??'null');
    if(recovery?.document?.id===id && M.fingerprint(recovery.document)!==state.saved && M.fingerprint(recovery.document)!==recovery.saved
      && await confirmDialog('Restore the unsaved local draft for this project?'+(recovery.saved!==state.saved?' The server revision has also changed; review before saving.':''))) state.doc=recovery.document;
    (recovery?.locked??[]).forEach(value=>locked.add(value));
  } catch { report('The local recovery draft could not be read. The saved project is open.','error'); }
  $('viewport-preset').value='project'; render(); fit(); await refreshStatus();
}
async function save() {
  const snapshot=M.clone(doc()), value=M.fingerprint(snapshot);
  const result=await api('/projects/'+snapshot.id,'PUT',snapshot);
  if(doc().id===snapshot.id) { state.saved=value; revision=result.revision; persistDraft(); }
  report('Saved. Deploy when you want players to see this revision.','success'); await refreshProjects(); controls();
}
async function deploy() {
  const snapshot=M.clone(doc()), value=M.fingerprint(snapshot);
  persistDraft(); report('Collecting linked drafts…');
  const bundle=await collect(snapshot,state.saved?JSON.parse(state.saved):null,async id=>{
    let saved=null;try{saved=await api('/projects/'+id);}catch(error){if(error.status!==404)throw error;}
    const recovery=JSON.parse(localStorage.getItem('wellui-draft:'+id)??'null');
    return chooseDraft(id,saved,recovery);
  });
  report('Validating, saving and publishing '+bundle.documents.length+' linked project(s)…');
  const result=await api('/deploy-bundle','POST',bundle);
  for(const row of bundle.documents) {
    const key='wellui-draft:'+row.document.id, recovery=JSON.parse(localStorage.getItem(key)??'null');
    if(recovery&&M.fingerprint(recovery.document)===M.fingerprint(row.document)) {
      recovery.saved=M.fingerprint(row.document);localStorage.setItem(key,JSON.stringify(recovery));
    }
  }
  if(doc().id===snapshot.id){state.saved=value;state.deployed=value;persistDraft();}
  report(serverStatus.runtime?.connected===false?'Linked projects exported locally. No Minecraft server is connected.':'Linked revisions published. Resource progress appears below.','success');
  serverStatus.deploymentMessages=result.messages; await refreshProjects(); revision=projects.find(p=>p.id===doc().id)?.revision??''; await refreshStatus(); controls();
}
async function refreshStatus() {
  if(!token || statusBusy) return; statusBusy=true;
  try {
    const data=await api('/status'); serverStatus={...serverStatus,...data};
    const runtime=data.runtime;
    $('connection').textContent=runtime.connected?'● Server connected':'● Local editor connected';
    const ready=(runtime.playerReadiness?.VERIFIED_LOADED??0)+(runtime.playerReadiness?.LOADED??0);
    $('backend').textContent=runtime.screenAvailable?(ready?'SCREEN ready · '+ready+' player'+(ready===1?'':'s'):'SCREEN available · waiting for players'):'SCREEN unavailable';
    const states={NOT_PREPARED:'Preparing resources',SOURCE_READY:'Source ready',PROVIDER_BUILD_REQUIRED:'Pack build required',PROVIDER_BUILDING:'Provider output changing',VERIFYING:'Verifying resources',VERIFIED:'Pack ready',FAILED:'Resource check failed'};
    $('pack-state').textContent=runtime.connected===false?'Export only · No live server':(runtime.provider??'Provider')+' · '+(states[runtime.packState]??runtime.packState);
    if(state && revision && data.deployedRevisions[doc().id]===revision) state.deployed=state.saved;
    $('advanced').textContent=JSON.stringify(serverStatus,null,2); controls();
  } catch(error) { $('connection').textContent='○ Server disconnected'; $('advanced').textContent=error.message; }
  finally { statusBusy=false; }
}
function renderPalette() {
  $('palette').replaceChildren(); const query=$('component-search').value.toLowerCase();
  palette.filter(([,name])=>name.toLowerCase().includes(query)).forEach(([kind,name])=> {
    const card=button('',()=>add(kind),'palette-card'); card.append(el('span','',icons[kind]),el('b','',name)); card.draggable=true;
    card.ondragstart=e=>e.dataTransfer.setData('application/wellui-component',kind); $('palette').append(card);
  });
}
function add(kind,x=60,y=60,asset='') {
  if(kind==='image' && !asset) { setTab('assets'); report('Upload or drag an image from your assets.'); return; }
  const node=M.component(kind,x,y,asset); change('Add '+kind,d=>{d.children.push(node);C.attach(d,node,kind);}); select([node.id]);
}
function setTab(name) {
  tab=name; document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===name));
  for(const id of ['components','layers','assets','projects']) $(id+'-panel').hidden=id!==name;
  if(name==='assets') run(refreshAssets); if(name==='projects') run(refreshProjects);
}
function renderLayers() {
  $('layers').replaceChildren(); if(!state) return;
  const walk=(nodes,depth)=>[...nodes].reverse().forEach(node=> {
    const item=M.find(doc(),node.id), row=el('div','layer-row'+(selection.includes(node.id)?' selected':'')+(node.style.opacity===0?' hidden-layer':''));
    row.setAttribute('role','treeitem'); row.setAttribute('aria-selected',selection.includes(node.id)); row.style.paddingLeft=(depth*12+4)+'px'; row.draggable=!isLocked(node.id);
    row.append(el('span','layer-icon',icons[node.type])); const label=el('span','label',node.id); label.title=node.id;label.tabIndex=0;label.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();select(e.shiftKey?[...selection,node.id]:[node.id]);}}; row.append(label);
    label.onclick=e=>select(e.shiftKey ? selection.includes(node.id)?selection.filter(id=>id!==node.id):[...selection,node.id]:[node.id]);
    label.ondblclick=async()=>{ const name=await nameDialog('Rename layer',node.id); if(!name) return; const id=name.trim().toLowerCase().replaceAll(' ','-'); if(!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(id) || M.find(doc(),id)) return; change('Rename layer',d=>{M.rename(d,node.id,id);},!isLocked(node.id)); select([id]); };
    const visibility=button(node.style.opacity===0?'○':'◉',()=>change('Toggle visibility',d=>{const n=M.find(d,node.id).node;n.style.opacity=n.style.opacity===0?1:0;},!isLocked(node.id)),'icon'); visibility.title='Hide / show layer';
    const lock=button(locked.has(node.id)?'▣':'◇',()=>{locked.has(node.id)?locked.delete(node.id):locked.add(node.id);render();queueDraft();},'icon'); lock.title='Lock / unlock editing'; row.append(visibility,lock);
    row.ondragstart=e=>{ if(!selection.includes(node.id)) selection=[node.id]; e.dataTransfer.setData('application/wellui-layers',JSON.stringify(editable())); };
    row.ondragend=()=>render();
    row.ondragover=e=>{e.preventDefault();row.classList.add('drag-over');}; row.ondragleave=()=>row.classList.remove('drag-over');
    row.ondrop=e=>{e.preventDefault();row.classList.remove('drag-over');const ids=JSON.parse(e.dataTransfer.getData('application/wellui-layers')||'[]'); if(!ids.length || isLocked(node.id)) return;change('Reorder layers',d=>M.parent(d,ids,e.shiftKey&&node.type==='panel'?node.id:item.parent,e.shiftKey&&node.type==='panel'?Infinity:item.index+1));};
    $('layers').append(row); if(node.children) walk(node.children,depth+1);
  }); walk(doc().children,0);
}
function copy() { if(state) clipboard=M.copy(doc(),selection); }
function paste() { if(!clipboard.length) return; let ids=[]; change('Paste layers',d=>{ids=M.paste(d,clipboard);});select(ids); }
function duplicate() { copy();paste(); }
function remove() { const ids=editable(); change('Delete layers',d=>M.remove(d,ids));selection=[];render(); }
function confirmDialog(message) {
  const dialog=$('confirm-dialog');
  $('confirm-message').textContent=message;
  dialog.returnValue='';
  return new Promise(resolve=>{
    dialog.addEventListener('close',()=>resolve(dialog.returnValue==='confirm'),{once:true});
    dialog.showModal();
    dialog.querySelector('button[value="cancel"]').focus();
  });
}
function nameDialog(title,name,hint='') {
  const dialog=$('rename-dialog'), input=$('rename-name');
  $('rename-title').textContent=title;$('rename-hint').textContent=hint;$('rename-label').textContent=title==='Rename project'?'Project name':'Name';
  dialog.returnValue='';input.value=name;
  return new Promise(resolve=>{
    dialog.addEventListener('close',()=>resolve(dialog.returnValue==='rename'?input.value:null),{once:true});
    dialog.showModal();input.focus();input.select();
  });
}
function renderProjects() {
  $('projects').replaceChildren();
  projects.forEach(entry=> {
    const card=el('article','project-card'+(doc()?.id===entry.id?' active':''));card.append(el('div','project-swatch',`${entry.width} × ${entry.height}`),el('h3','',(entry.slug?entry.slug+' — ':'')+entry.name));
    card.append(el('p','',`${entry.valid?'Valid design':'Needs attention'} · ${entry.deployedRevision?entry.revision===entry.deployedRevision?'Deployed':'Deployment outdated':'Not deployed'}`));
    card.append(el('p','',new Date(entry.modified).toLocaleString()));if(entry.deployedRevision)card.append(el('p','',serverStatus.runtime?.screenAvailable?(serverStatus.runtime.packState==='VERIFIED'?'SCREEN resources ready · client load required':'SCREEN resources pending'):'SCREEN unavailable'));const actions=el('div','row');
    actions.append(button('Open',()=>run(()=>openProject(entry.id))),button('Duplicate',()=>run(async()=>{if(state?.dirty&&!await confirmDialog('Keep your current changes as a local draft and duplicate this project?'))return;persistDraft();const d=L.duplicateProject(await api('/projects/'+entry.id),M.uid('project'));state=new M.History(d);state.saved='';selection=[];await save();render();fit();})),button('Rename',()=>run(async()=>{const name=await nameDialog('Rename project',entry.name,'The command slug and internal links stay the same.');if(!name?.trim())return;const d=await api('/projects/'+entry.id);d.name=name;const result=await api('/projects/'+entry.id,'PUT',d);if(doc()?.id===entry.id){acceptRename(state,d);revision=result.revision;serverIssues=[];persistDraft();render();}await refreshProjects();})),button('Delete',()=>run(async()=>{if(!await confirmDialog(`Delete “${entry.name}” and remove its deployed revision? A server backup is retained.`))return;await api('/projects/'+entry.id,'DELETE');localStorage.removeItem('wellui-draft:'+entry.id);await refreshProjects();if(doc()?.id===entry.id){state=null;if(projects.length)await openProject(projects[0].id,true);else newProject(M.template('blank','Untitled project'));}})));
    card.append(actions);$('projects').append(card);
  });
}
async function imageUrl(id) {
  if(urls.has(id)) return urls.get(id); if(loadingImages.has(id)||failedImages.has(id)) return null;
  loadingImages.add(id); try { const blob=await api('/assets/'+id);const url=URL.createObjectURL(blob);urls.set(id,url);return url; }
  catch { failedImages.add(id);return null; } finally {loadingImages.delete(id);}
}
async function refreshAssets() { assets=await api('/assets'); renderAssets();renderIssues(); }
function renderAssets() {
  $('assets').replaceChildren(); const usage=state?M.assetUsage(doc()):{};
  const retained=new Set(); if(state) for(const entry of [...state.past,...state.future]) Object.keys(M.assetUsage(JSON.parse(entry.value))).forEach(id=>retained.add(id));
  assets.filter(a=>a.name.toLowerCase().includes($('asset-search').value.toLowerCase())).forEach(a=>{
    const card=el('article','asset-card');card.draggable=true;card.ondragstart=e=>e.dataTransfer.setData('application/wellui-asset',a.id);
    const img=el('img');img.alt=a.name;if(urls.has(a.id))img.src=urls.get(a.id);else imageUrl(a.id).then(url=>{if(url)img.src=url;});img.ondblclick=()=>add('image',60,60,a.id);card.append(img);
    const caption=el('div','asset-caption');caption.append(el('strong','',a.name),el('small','',`${a.width} × ${a.height} · ${(a.bytes/1024).toFixed(1)} KB`),el('small','',`${usage[a.id]??0} in this project · ${a.usage} saved uses`));
    const actions=el('div','asset-actions');actions.append(button('Add',()=>add('image',60,60,a.id)),button('Replace',()=>{uploadTarget={asset:a.id};$('file-input').click();}));
    const del=button('×',()=>run(async()=>{if(!await confirmDialog('Delete this unused image?'))return;await api('/assets/'+a.id,'DELETE');URL.revokeObjectURL(urls.get(a.id));urls.delete(a.id);await refreshAssets();}));const reasons=[...(a.protectionReasons??(a.protected?['Saved revision or retained backup']:[])),...(usage[a.id]?['Current unsaved design']:[]),...(retained.has(a.id)?['Undo / redo history']:[])];del.disabled=a.protected||!!usage[a.id]||retained.has(a.id);del.title=del.disabled?'Protected: '+reasons.join('; '):'Delete unused image';if(del.disabled)caption.append(el('small','asset-protection',reasons.join('; ')));actions.append(del);caption.append(actions);card.append(caption);$('assets').append(card);
  });
}
function view() {
  const preset=$('viewport-preset').value, [width,height]=preset==='project'?[doc().width,doc().height]:preset.split('x').map(Number);
  const scale=Math.min(width/doc().width,height/doc().height);
  return {width,height,scale,x:(width-doc().width*scale)/2,y:(height-doc().height*scale)/2};
}
function point(e) { const r=$('viewport').getBoundingClientRect(),v=view();return {x:((e.clientX-r.left-pan.x)/zoom-v.x)/v.scale,y:((e.clientY-r.top-pan.y)/zoom-v.y)/v.scale}; }
function zoomTo(next,cx=$('viewport').clientWidth/2,cy=$('viewport').clientHeight/2) { if(mode==='logic'){logic.zoomTo(next);return;}next=Math.max(.1,Math.min(3,next));pan={x:cx-(cx-pan.x)*next/zoom,y:cy-(cy-pan.y)*next/zoom};zoom=next;renderCanvas(); }
function fit() { if(!state)return;if(mode==='logic'){logic.fit();return;}const v=view(),vp=$('viewport');zoom=Math.min(1.5,Math.max(.1,Math.min((vp.clientWidth-90)/v.width,(vp.clientHeight-80)/v.height)));pan={x:(vp.clientWidth-v.width*zoom)/2,y:(vp.clientHeight-v.height*zoom)/2};renderCanvas(); }
function boxStyle(node,b) { Object.assign(node.style,{left:b.x+'px',top:b.y+'px',width:b.width+'px',height:b.height+'px'}); }
function renderCanvas() {
  if(!state)return;if(mode==='animate'){animate.preview();return;}if(mode==='logic'){logic.renderCanvas();return;} const v=view(),stage=$('stage'),canvas=$('canvas'),overlay=$('overlay');
  Object.assign(stage.style,{width:v.width+'px',height:v.height+'px',transform:`translate(${pan.x}px,${pan.y}px) scale(${zoom})`});
  for(const layer of [canvas,overlay]) Object.assign(layer.style,{left:v.x+'px',top:v.y+'px',width:doc().width+'px',height:doc().height+'px',transform:`scale(${v.scale})`});
  canvas.replaceChildren();overlay.replaceChildren();$('empty-canvas').hidden=doc().children.length>0;
  const items=M.flatten(doc());items.forEach(item=>{
    const n=item.node,node=el('div','design-node'+(isLocked(n.id)?' locked':''));node.dataset.id=n.id;node.dataset.type=n.type;boxStyle(node,item);
    const content=el('div','content');Object.assign(content.style,{opacity:item.opacity,background:n.type==='image'?'transparent':n.style.background,color:n.style.color,fontSize:n.style.fontSize+'px'});
    if(n.text!==undefined)content.textContent=n.text;
    if(n.type==='image'){const image=el('img');image.alt='';if(urls.has(n.asset))image.src=urls.get(n.asset);else if(!failedImages.has(n.asset))imageUrl(n.asset).then(url=>{if(url)renderCanvas();});content.append(image);}
    paint(content,doc().controls?.[n.id],n.text??'',previewState);
    const clip=clippedBounds(doc(),item,items);
    if(clip)node.style.clipPath=`inset(${Math.max(0,clip.y-item.y)}px ${Math.max(0,item.x+item.width-clip.x-clip.width)}px ${Math.max(0,item.y+item.height-clip.y-clip.height)}px ${Math.max(0,clip.x-item.x)}px)`;
    node.append(content);canvas.append(node);
    if(selection.includes(n.id)&&!preview){const outline=el('div','selection-outline');boxStyle(outline,item);overlay.append(outline);}
  });
  const b=M.bounds(M.roots(doc(),selection));if(b&&!preview){const outline=el('div','selection-outline');boxStyle(outline,b);const size=el('span','selection-size',`${Math.round(b.width)} × ${Math.round(b.height)}`);size.style.fontSize=(10/(zoom*v.scale))+'px';outline.append(size);if(editable().length===M.roots(doc(),selection).length){const handle=el('span','resize-handle');handle.dataset.resize='true';handle.style.width=handle.style.height=(9/(zoom*v.scale))+'px';outline.append(handle);}overlay.append(outline);}
  guides.forEach(g=>{const line=el('div','guide');boxStyle(line,g.axis==='x'?{x:g.at,y:0,width:1/(zoom*v.scale),height:doc().height}:{x:0,y:g.at,width:doc().width,height:1/(zoom*v.scale)});overlay.append(line);});
  if(marquee){const m=el('div','marquee');boxStyle(m,marquee);overlay.append(m);}
  $('zoom').value=Math.round(zoom*100);$('canvas-size').textContent=`${doc().width} × ${doc().height}`;$('canvas-name').textContent=doc().name;
}
function renderIssues() {
  if(!state)return;const issues=[...M.validation(doc(),assets.length?new Set(assets.map(a=>a.id)):null),...L.validation(doc()),...A.validation(doc()),...C.validation(doc()),...serverIssues];$('issue-count').textContent=issues.length;$('issues').replaceChildren();
  issues.slice(0,30).forEach(issue=>{const b=button((issue.nodeId?'Node '+issue.nodeId+': ':issue.componentId?issue.componentId+': ':'')+(issue.message??t(issue.code)),()=>{if(issue.animationId){setMode('animate');animate.id=issue.animationId;animate.render();}else if(issue.nodeId){setMode('logic');logic.focus(issue.nodeId);}else if(issue.componentId){setMode('design');select([issue.componentId]);}},'issue '+(issue.level==='warning'?'warning':'error'));$('issues').append(b);});
}
function render() {
  if(!state)return;selection=selection.filter(id=>M.find(doc(),id));$('project-name').value=doc().name;$('selection-count').textContent=selection.length?`${selection.length} selected`:'Canvas';
  renderCanvas();renderLayers();renderIssues();if(tab==='assets')renderAssets();
  if(mode==='animate'){animate.render();$('selection-count').textContent='Timeline';controls();return;}
  if(mode==='logic'){logic.render();$('selection-count').textContent=logic.ids.length?logic.ids.length+' nodes':'Graph';controls();return;}
  inspector($('inspector'),{editLogic,doc:doc(),ids:selection,locked:new Set(M.flatten(doc()).filter(i=>isLocked(i.node.id)).map(i=>i.node.id)),change,select,duplicate,remove,replaceImage:()=>{uploadTarget={node:selection[0]};$('file-input').click();}});if(selection.length===1)$('inspector').append(button('Open in Animate',()=>{setMode('animate');animate.focus(selection[0]);},'wide secondary'));controls();
}
function newProject(document) {animate.pause();persistDraft();state=new M.History(document);state.saved='';revision='';selection=[];logic.ids=[];logic.edge=null;logic.wire=null;locked.clear();serverIssues=[];render();fit();queueDraft();}

document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>setTab(b.dataset.tab));
$('component-search').oninput=renderPalette;$('asset-search').oninput=renderAssets;
$('undo').onclick=()=>{state.undo();render();queueDraft();};$('redo').onclick=()=>{state.redo();render();queueDraft();};
$('save').onclick=()=>run(save);$('deploy').onclick=()=>run(deploy);
$('validate').onclick=()=>run(async()=>{serverIssues=(await api('/validate','POST',doc())).issues;renderIssues();report(serverIssues.length?'Review the design checks.':'Design validated. Browser warnings may still need review.',serverIssues.length?'error':'success');});
$('project-name').onchange=()=>change('Rename project',d=>{d.name=$('project-name').value;});
$('preview').onclick=()=>{preview=!preview;$('workspace').classList.toggle('previewing',preview);$('preview').textContent=preview?'Back to edit':'Preview';render();report(preview?'Design preview. Text metrics and Minecraft appearance may differ.':'Editing resumed.');};
const statePreview=el('select');statePreview.setAttribute('aria-label','Control state preview');statePreview.title='Preview a control state without changing saved values';
for(const state of C.states){const option=el('option','',state.toLowerCase());option.value=state;statePreview.append(option);}
statePreview.onchange=()=>{previewState=statePreview.value;renderCanvas();};$('preview').after(statePreview);
$('fit').onclick=fit;$('actual').onclick=()=>zoomTo(1);$('zoom').onchange=()=>zoomTo(Number($('zoom').value)/100);$('viewport-preset').onchange=fit;
$('advanced-toggle').onclick=()=>{$('advanced').hidden=!$('advanced').hidden;};
$('group').onclick=()=>{const ids=editable();let id;change('Group layers',d=>{id=M.group(d,ids);});if(id)select([id]);};
$('ungroup').onclick=()=>change('Move layers to root',d=>M.parent(d,editable(),null));
$('layer-root').ondragover=e=>e.preventDefault();$('layer-root').ondrop=e=>{e.preventDefault();const ids=JSON.parse(e.dataTransfer.getData('application/wellui-layers')||'[]');change('Move layers to root',d=>M.parent(d,ids,null));};
const layerActions=el('div','row');layerActions.append(button('Duplicate',duplicate),button('Delete',remove));$('layers-panel').insertBefore(layerActions,$('layer-root'));
for(const id of ['starter','new-project']) $(id).onclick=()=>$('new-dialog').showModal();
$('new-cancel').onclick=()=>$('new-dialog').close();$('new-form').onsubmit=async e=>{e.preventDefault();const kind=new FormData(e.currentTarget).get('template');if(state?.dirty&&!await confirmDialog('Keep your changes as a local draft and create a project?'))return;const name=$('new-name').value.trim();$('new-dialog').close();if(kind==='animated'){run(async()=>{const image=await fetch('/animation-demo.png').then(r=>r.blob()),asset=await api('/assets','POST',image,'Animation demo crest');const destination=M.template('confirm','Animation destination');await api('/projects/'+destination.id,'PUT',destination);const menu=A.demo(name,asset.asset,destination.id);menu.id=M.uid('project');newProject(menu);await refreshAssets();await refreshProjects();setMode('animate');});}else newProject(M.template(kind,name));};
$('upload').onclick=()=>{uploadTarget=null;$('file-input').click();};
$('file-input').onchange=()=>run(async()=>{
  const file=$('file-input').files[0];$('file-input').value='';if(!file)return;if(file.type!=='image/png'||file.size>maxAssetBytes)throw new Error('Choose a PNG within the server upload limit.');
  const target=uploadTarget;uploadTarget=null;const result=await api('/assets','POST',file,file.name);await refreshAssets();
  if(target)change('Replace image',d=>M.flatten(d).forEach(({node})=>{if(node.type==='image'&&(node.id===target.node||node.asset===target.asset))node.asset=result.asset;}));
  else add('image',60,60,result.asset);report('Image uploaded. Deploy when ready; linked drafts are saved automatically.','success');
});
$('viewport').ondragover=e=>e.preventDefault();$('viewport').ondrop=e=>{e.preventDefault();if(!state||preview)return;const p=point(e),kind=e.dataTransfer.getData('application/wellui-component'),asset=e.dataTransfer.getData('application/wellui-asset');if(kind)add(kind,p.x,p.y);else if(asset)add('image',p.x,p.y,asset);};
$('viewport').onwheel=e=>{if(!state)return;e.preventDefault();const r=$('viewport').getBoundingClientRect();zoomTo(zoom*Math.exp(-e.deltaY*.0015),e.clientX-r.left,e.clientY-r.top);};
$('viewport').onpointerdown=e=>{
  if(!state || e.button>1)return;const vp=$('viewport');vp.focus();
  if(space||e.button===1){pointer={kind:'pan',client:{x:e.clientX,y:e.clientY},pan:{...pan}};vp.classList.add('panning');}
  else if(preview)return;
  else {const p=point(e),id=e.target.closest('[data-id]')?.dataset.id,resize=e.target.closest('[data-resize]');
    if(resize){pointer={kind:'resize',start:p,before:M.fingerprint(doc()),ids:editable(),box:M.bounds(M.roots(doc(),editable()))};}
    else if(id&&!isLocked(id)&&!e.ctrlKey&&!e.metaKey){
      if(e.shiftKey)select(selection.includes(id)?selection.filter(value=>value!==id):[...selection,id]);else if(!selection.includes(id))select([id]);
      pointer={kind:'move',start:p,before:M.fingerprint(doc()),ids:editable(),box:M.bounds(M.roots(doc(),editable()))};
    }else {if(!e.shiftKey)select([]);pointer={kind:'marquee',start:p,previous:[...selection]};}
  }
  vp.setPointerCapture(e.pointerId);e.preventDefault();
};
$('viewport').onpointermove=e=>{
  if(!pointer)return;
  if(pointer.kind==='pan'){pan={x:pointer.pan.x+e.clientX-pointer.client.x,y:pointer.pan.y+e.clientY-pointer.client.y};renderCanvas();return;}
  const p=point(e),dx=p.x-pointer.start.x,dy=p.y-pointer.start.y;
  if(pointer.kind==='marquee'){marquee={x:Math.min(p.x,pointer.start.x),y:Math.min(p.y,pointer.start.y),width:Math.abs(dx),height:Math.abs(dy)};selection=[...new Set([...pointer.previous,...M.flatten(doc()).filter(i=>!isLocked(i.node.id)&&i.x>=marquee.x&&i.y>=marquee.y&&i.x+i.width<=marquee.x+marquee.width&&i.y+i.height<=marquee.y+marquee.height).map(i=>i.node.id)])];renderCanvas();return;}
  if(!pointer.box)return;state.doc=JSON.parse(pointer.before);guides=[];
  if(pointer.kind==='move'){const proposed={...pointer.box,x:pointer.box.x+dx,y:pointer.box.y+dy};const snap=e.altKey?{dx:0,dy:0,guides:[]}:M.snap(doc(),pointer.ids,proposed,6/(zoom*view().scale));M.translate(doc(),pointer.ids,dx+snap.dx,dy+snap.dy);guides=snap.guides;}
  else M.resize(doc(),pointer.ids,{...pointer.box,width:Math.max(1,pointer.box.width+dx),height:Math.max(1,pointer.box.height+dy)});
  renderCanvas();
};
function endPointer(cancel=false){if(!pointer)return;if(pointer.before){if(cancel)state.doc=JSON.parse(pointer.before);else state.accept(pointer.before,pointer.kind==='move'?'Move layers':'Resize layers');}pointer=null;guides=[];marquee=null;$('viewport').classList.remove('panning');render();queueDraft();}
$('viewport').onpointerup=()=>endPointer();$('viewport').onpointercancel=()=>endPointer(true);
window.addEventListener('keydown',e=>{
  if(e.target.closest('input,textarea,select,[contenteditable=true]')||document.querySelector('dialog[open]'))return;
  if(mode==='animate'&&state){if(animate.key(e))return;if(!(e.ctrlKey||e.metaKey)||e.key.toLowerCase()!=='z')return;}
  if(mode==='logic'&&state&&logic.key(e))return;
  if(e.code==='Space'){if(mode==='logic')return;space=true;e.preventDefault();return;}if(!state||preview)return;
  const mod=e.ctrlKey||e.metaKey,key=e.key.toLowerCase();
  if(mod&&['z','c','v','d'].includes(key)){e.preventDefault();if(key==='z'){e.shiftKey?state.redo():state.undo();render();queueDraft();}else if(key==='c')copy();else if(key==='v')paste();else duplicate();}
  else if(['Delete','Backspace'].includes(e.key)){e.preventDefault();remove();}
  else if(e.key.startsWith('Arrow')){e.preventDefault();const amount=e.shiftKey?10:1;change('Nudge layers',d=>M.translate(d,editable(),e.key==='ArrowLeft'?-amount:e.key==='ArrowRight'?amount:0,e.key==='ArrowUp'?-amount:e.key==='ArrowDown'?amount:0));}
  else if(e.key==='Escape'){endPointer(true);select([]);}
});
window.addEventListener('keyup',e=>{if(e.code==='Space')space=false;});window.addEventListener('blur',()=>{space=false;endPointer(true);});
window.addEventListener('beforeunload',e=>{persistDraft();if(state?.dirty){e.preventDefault();e.returnValue='';}});
$('auth-form').onsubmit=e=>{e.preventDefault();const text=$('auth-token').value.trim();try{token=new URL(text).hash.slice(1);token=new URLSearchParams(token).get('token')??'';}catch{token=text;}run(connect);};
async function connect(){
  try{const session=await api('/session');L.configure(await api('/logic-catalog'));maxAssetBytes=session.maxAssetBytes;sessionStorage.setItem('wellui-token',token);$('auth-dialog').close();await refreshProjects();if(!state){if(projects.length)await openProject(projects[0].id,true);else newProject(M.template('blank','Untitled project'));}await refreshAssets();await refreshStatus();report('Workspace connected. Your design is ready to edit.','success');}
  catch(error){$('auth-error').textContent=error.message;if(!$('auth-dialog').open)$('auth-dialog').showModal();throw error;}
}
renderPalette();if(token)await run(connect);else $('auth-dialog').showModal();
setInterval(()=>{if(!document.hidden)refreshStatus();},5000);
new ResizeObserver(()=>{if(state&&!pointer)renderCanvas();}).observe($('viewport'));
