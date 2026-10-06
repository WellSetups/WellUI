import * as L from './logic-model.mjs';
const el=(tag,cls='',text)=>{const n=document.createElement(tag);n.className=cls;if(text!==undefined)n.textContent=text;return n;};
const button=(title,work)=>{const n=el('button','',title);n.onclick=work;return n;};
const portOrder=ports=>Object.entries(ports).sort(([a],[b])=>a.localeCompare(b));
const title=key=>key.replaceAll('_',' ').replace(/^./,c=>c.toUpperCase());
export class LogicEditor {
  constructor({root,palette,inspector,document:doc,change,report,projects,issues}) {
    Object.assign(this,{root,palette,inspector,doc,change,report,projects,issues});this.ids=[];this.clipboard=null;this.zoom=.85;this.pan={x:40,y:40};this.query='';this.wire=null;this.drag=null;this.edge=null;
    root.tabIndex=0;root.setAttribute('aria-label','Logic canvas. Drag background to pan. Wheel to zoom.');
    root.onwheel=e=>{e.preventDefault();const r=root.getBoundingClientRect();this.zoomTo(this.zoom*Math.exp(-e.deltaY*.0015),e.clientX-r.left,e.clientY-r.top);};
    root.onpointerdown=e=>this.down(e);root.onpointermove=e=>this.move(e);root.onpointerup=e=>this.up(e);root.onpointercancel=()=>{this.drag=null;this.renderCanvas();};
  }
  point(e){const r=this.root.getBoundingClientRect();return{x:(e.clientX-r.left-this.pan.x)/this.zoom,y:(e.clientY-r.top-this.pan.y)/this.zoom};}
  zoomTo(value,x=this.root.clientWidth/2,y=this.root.clientHeight/2){value=Math.min(2,Math.max(.2,value));this.pan={x:x-(x-this.pan.x)*value/this.zoom,y:y-(y-this.pan.y)*value/this.zoom};this.zoom=value;this.renderCanvas();}
  fit(ids=null){const nodes=L.graph(this.doc()).nodes.filter(n=>!ids||ids.includes(n.id));if(!nodes.length){this.pan={x:40,y:40};this.zoom=.85;this.renderCanvas();return;}
    const x=Math.min(...nodes.map(n=>n.x)),y=Math.min(...nodes.map(n=>n.y)),w=Math.max(...nodes.map(n=>n.x+244))-x,h=Math.max(...nodes.map(n=>n.y+this.height(n)))-y;
    this.zoom=Math.min(1.1,Math.max(.2,Math.min((this.root.clientWidth-100)/w,(this.root.clientHeight-100)/h)));this.pan={x:(this.root.clientWidth-w*this.zoom)/2-x*this.zoom,y:(this.root.clientHeight-h*this.zoom)/2-y*this.zoom};this.renderCanvas();
  }
  focus(id){this.ids=[id];this.edge=null;this.render();this.fit(this.ids);}
  height(n){const s=L.spec(n,this.doc());return 60+Math.max(Object.keys(s.inputs).length,Object.keys(s.outputs).length,1)*28;}
  endpoint(n,port,input){const entries=portOrder(L.spec(n,this.doc())[input?'inputs':'outputs']);return{x:n.x+(input?0:244),y:n.y+60+Math.max(0,entries.findIndex(([p])=>p===port))*28};}
  path(a,b){const d=Math.max(55,Math.abs(b.x-a.x)*.5);return`M ${a.x} ${a.y} C ${a.x+d} ${a.y}, ${b.x-d} ${b.y}, ${b.x} ${b.y}`;}
  render(){this.ids=this.ids.filter(id=>L.graph(this.doc()).nodes.some(n=>n.id===id));this.renderPalette();this.renderCanvas();this.renderInspector();}
  renderPalette(){this.palette.replaceChildren();const heading=el('div','panel-heading');heading.append(el('h2','','Build a behavior'),el('p','','Start with an event, then connect actions.'));this.palette.append(heading);
    const search=el('input');search.type='search';search.placeholder='Search nodes';search.setAttribute('aria-label','Search logic nodes');search.value=this.query;
    search.oninput=()=>{this.query=search.value;this.renderPalette();const next=this.palette.querySelector('input');next.focus();next.setSelectionRange?.(this.query.length,this.query.length);};this.palette.append(search);
    const addAt=()=>({x:(80-this.pan.x)/this.zoom,y:(80-this.pan.y)/this.zoom});
    for(const category of ['event','condition','flow','action','value']){const list=L.entries().filter(([type,s])=>s.category===category&&(L.labels[type]??type).toLowerCase().includes(this.query.toLowerCase()));if(!list.length)continue;
      this.palette.append(el('h3','logic-category',category==='flow'?'Control flow':title(category)));
      for(const [type] of list){const b=button(L.labels[type]??title(type),()=>{let id;this.change('Add logic node',d=>{const p=addAt();id=L.create(d,type,p.x,p.y).id;});this.ids=[id];this.render();});b.className='logic-palette '+category;b.draggable=true;b.ondragstart=e=>e.dataTransfer.setData('application/wellui-logic',type);this.palette.append(b);}
    }
    if(this.query){const matches=L.graph(this.doc()).nodes.filter(n=>(L.labels[n.type]+' '+n.id).toLowerCase().includes(this.query.toLowerCase()));if(matches.length)this.palette.append(el('h3','logic-category','In this graph'));matches.forEach(n=>this.palette.append(button((L.labels[n.type]??n.type)+' · '+n.id.slice(-5),()=>this.focus(n.id))));}
    this.palette.append(button('Session variables',()=>{this.ids=[];this.renderInspector();}));
    this.root.ondragover=e=>e.preventDefault();this.root.ondrop=e=>{e.preventDefault();const type=e.dataTransfer.getData('application/wellui-logic');if(!type)return;const p=this.point(e);let id;this.change('Add logic node',d=>{id=L.create(d,type,p.x,p.y).id;});this.focus(id);};
  }
  renderCanvas(){this.root.replaceChildren();const stage=el('div','logic-stage');stage.style.transform=`translate(${this.pan.x}px,${this.pan.y}px) scale(${this.zoom})`;this.root.append(stage);
    const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.classList.add('logic-edges');stage.append(svg);const g=L.graph(this.doc());
    const positions=new Map(g.nodes.map(n=>[n.id,{...n}]));if(this.drag?.kind==='nodes')for(const id of this.ids){const n=positions.get(id);if(n){n.x+=this.drag.dx;n.y+=this.drag.dy;}}
    const path=(a,b,cls,click)=>{const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',this.path(a,b));p.setAttribute('class',cls);if(click){p.style.pointerEvents='stroke';p.onpointerdown=e=>{e.stopPropagation();click();};}svg.append(p);};
    g.edges.forEach((edge,index)=>{const a=positions.get(edge.from),b=positions.get(edge.to);if(!a||!b)return;path(this.endpoint(a,edge.output,false),this.endpoint(b,edge.input,true),'logic-wire '+L.spec(a,this.doc()).outputs[edge.output]+(this.edge===index?' selected':''),()=>{this.edge=index;this.ids=[];this.render();});});
    if(this.wire&&positions.has(this.wire.from)&&this.wire.point)path(this.endpoint(positions.get(this.wire.from),this.wire.output,false),this.wire.point,'logic-wire pending');
    const problems=[...L.validation(this.doc()),...(this.issues?.()??[])];
    for(const n of positions.values()){const s=L.spec(n,this.doc()),card=el('article','logic-node '+s.category+(this.ids.includes(n.id)?' selected':''));const problem=problems.find(p=>p.nodeId===n.id);if(problem){card.classList.add(problem.level==='warning'?'node-warning':'node-error');card.title=problem.message??problem.code;}card.dataset.node=n.id;card.style.left=n.x+'px';card.style.top=n.y+'px';card.style.height=this.height(n)+'px';
      const head=el('div','logic-node-heading');const component=n.settings.component?.value;const find=nodes=>{for(const item of nodes){if(item.id===component)return item;const child=find(item.children??[]);if(child)return child;}};const caption=component?(find(this.doc().children)?.text??component):(n.settings.project?.value?(this.projects().find(p=>p.id===n.settings.project.value)?.name??'Missing project'):undefined)??n.settings.variable?.value??n.settings.permission?.value??(n.type==='delay'?(n.settings.ticks?.value??'')+' ticks':n.settings.value?.value??'');head.append(el('small','',title(s.category)+(caption?' · '+caption.slice(0,28):'')),el('strong','',L.labels[n.type]??title(n.type)));card.append(head);
      const input=portOrder(s.inputs),output=portOrder(s.outputs);const ports=el('div','logic-ports');
      for(let i=0;i<Math.max(input.length,output.length,1);i++){const row=el('div','logic-port-row');for(const [isInput,list] of [[true,input],[false,output]]){if(!list[i]){row.append(el('span'));continue;}const [port,type]=list[i];const b=el('button','logic-port '+(isInput?'input ':'output ')+type,port==='in'?'In':port==='next'?'Next':title(port));Object.assign(b.dataset,{port,node:n.id,direction:isInput?'input':'output'});b.title=`${isInput?'Input':'Output'}: ${type}`;
          if(this.wire&&isInput)b.classList.add(L.canConnect(this.doc(),this.wire.from,this.wire.output,n.id,port)?'compatible':'incompatible');row.append(b);}ports.append(row);}
      card.append(ports);stage.append(card);
    }
    if(!g.nodes.length){const empty=el('div','logic-empty','Give your interface a little logic.');empty.append(el('p','','Choose an event from the palette, or select a button in Design and choose Edit Logic.'));this.root.append(empty);}
    const status=el('div','logic-status',`${g.nodes.length} / 128 nodes · ${g.edges.length} connections · ${Math.round(this.zoom*100)}%`);this.root.append(status);document.getElementById('zoom').value=Math.round(this.zoom*100);
  }
  down(e){if(e.button>1)return;this.root.focus();const port=e.target.closest('[data-port]');
    if(port){e.preventDefault();if(port.dataset.direction==='output'){this.wire={from:port.dataset.node,output:port.dataset.port,point:this.point(e)};this.drag={kind:'wire'};this.renderCanvas();}else if(this.wire)this.complete(port.dataset.node,port.dataset.port);return;}
    this.wire=null;const node=e.target.closest('[data-node]');this.edge=null;
    if(node&&e.button===0){const id=node.dataset.node;if(e.shiftKey)this.ids=this.ids.includes(id)?this.ids.filter(n=>n!==id):[...this.ids,id];else if(!this.ids.includes(id))this.ids=[id];this.drag={kind:'nodes',start:this.point(e),dx:0,dy:0};}
    else{if(!e.shiftKey)this.ids=[];this.drag={kind:'pan',x:e.clientX,y:e.clientY,pan:{...this.pan}};}
    this.root.setPointerCapture(e.pointerId);e.preventDefault();this.renderCanvas();this.renderInspector();
  }
  move(e){if(this.wire){this.wire.point=this.point(e);this.renderCanvas();}if(!this.drag)return;
    if(this.drag.kind==='nodes'){const p=this.point(e);this.drag.dx=p.x-this.drag.start.x;this.drag.dy=p.y-this.drag.start.y;this.renderCanvas();}
    if(this.drag.kind==='pan'){this.pan={x:this.drag.pan.x+e.clientX-this.drag.x,y:this.drag.pan.y+e.clientY-this.drag.y};this.renderCanvas();}
  }
  up(e){if(this.drag?.kind==='wire'){const port=document.elementFromPoint(e.clientX,e.clientY)?.closest('[data-port][data-direction=input]');if(port)this.complete(port.dataset.node,port.dataset.port);}
    if(this.drag?.kind==='nodes'){const {dx,dy}=this.drag;this.drag=null;if(dx||dy)this.change('Move logic nodes',d=>L.graph(d).nodes.filter(n=>this.ids.includes(n.id)).forEach(n=>{n.x=Math.max(-100000,Math.min(100000,n.x+dx));n.y=Math.max(-100000,Math.min(100000,n.y+dy));}));}
    this.drag=null;this.renderCanvas();
  }
  complete(to,input){const wire=this.wire;this.wire=null;this.drag=null;if(!wire)return;this.change('Connect logic nodes',d=>L.connect(d,wire.from,wire.output,to,input));this.render();}
  copy(){this.clipboard=L.copy(this.doc(),this.ids);}
  paste(){if(!this.clipboard)return;let ids=[];this.change('Paste logic nodes',d=>{ids=L.paste(d,this.clipboard);});this.ids=ids;this.render();}
  duplicate(){this.copy();this.paste();}
  remove(){if(this.edge!==null){const index=this.edge;this.edge=null;this.change('Delete connection',d=>L.ensure(d).edges.splice(index,1));}else this.change('Delete logic nodes',d=>L.remove(d,this.ids));this.ids=[];this.render();}
  key(e){const mod=e.ctrlKey||e.metaKey,key=e.key.toLowerCase();if(mod&&['c','v','d'].includes(key)){e.preventDefault();this[key==='c'?'copy':key==='v'?'paste':'duplicate']();return true;}
    if(['Delete','Backspace'].includes(e.key)){e.preventDefault();this.remove();return true;}if(e.key==='Escape'){this.wire=null;this.ids=[];this.edge=null;this.render();return true;}
    if(e.key.startsWith('Arrow')){e.preventDefault();const delta=e.shiftKey?10:1;this.change('Nudge logic nodes',d=>L.graph(d).nodes.filter(n=>this.ids.includes(n.id)).forEach(n=>{n.x+=(e.key==='ArrowRight'?delta:e.key==='ArrowLeft'?-delta:0);n.y+=(e.key==='ArrowDown'?delta:e.key==='ArrowUp'?-delta:0);}));return true;}return false;
  }
  renderInspector(){document.getElementById('selection-count').textContent=this.ids.length?this.ids.length+' selected':'Graph';const root=this.inspector;root.replaceChildren();const section=title=>{const s=el('section','inspector-section');s.append(el('h3','',title));root.append(s);return s;};
    const field=(s,label,current,callback,options=null,type='text')=>{const l=el('label','field',label),input=el(options?'select':type==='textarea'?'textarea':'input');if(options)options.forEach(o=>{const opt=el('option','',o.label??o);opt.value=o.value??o;input.append(opt);});else if(type!=='textarea')input.type=type;input.value=current;input.setAttribute('aria-label',label);input.onchange=()=>callback(input.value);l.append(input);s.append(l);return input;};
    if(this.edge!==null){const s=section('Connection');s.append(el('p','','Select Delete to disconnect these ports.'),button('Delete connection',()=>this.remove()));return;}
    const node=L.graph(this.doc()).nodes.find(n=>n.id===this.ids[0]);
    if(node){const s=section(this.ids.length>1?`${this.ids.length} selected nodes`:L.labels[node.type]??node.type);const actions=el('div','row');actions.append(button('Duplicate',()=>this.duplicate()),button('Delete',()=>this.remove()));s.append(actions);if(this.ids.length>1)return;
      s.append(el('p','hint',node.id));const spec=L.spec(node,this.doc());
      const update=(key,type,raw)=>this.change('Edit logic node',d=>{const n=L.graph(d).nodes.find(n=>n.id===node.id);if(type==='NUMBER'&&(!Number.isFinite(Number(raw))||Math.abs(Number(raw))>1e12))throw new Error('Enter a finite number.');n.settings[key]=L.value(type,raw);
        if(key==='variable'&&n.type==='set_variable'){const kind=L.spec(n,d).inputs.value;n.settings.value=L.value(kind);}
        if(key==='component')L.controlTarget(d,n);
      });
      for(const [key,type] of Object.entries(spec.settings)){
        if(key==='valueType')continue;
        let choices=null;
        if(key==='component'){const items=[];const walk=nodes=>nodes.forEach(n=>{if(L.acceptsComponent(this.doc(),node.type,n))items.push({value:n.id,label:n.id});walk(n.children??[]);});walk(this.doc().children);choices=[{value:'',label:'Choose a component'},...items];}
        if(key==='animation')choices=[{value:'',label:'Choose an animation'},...(this.doc().animations?.items??[]).map(a=>({value:a.id,label:a.name}))];
        if(key==='project')choices=[...new Set([this.doc().id,...this.projects().map(p=>p.id)])].map(id=>({value:id,label:this.projects().find(p=>p.id===id)?.name??id}));
        if(key==='variable')choices=[{value:'',label:'Choose a variable'},...L.graph(this.doc()).variables.filter(v=>node.type==='add_number'?v.type==='NUMBER':node.type==='toggle_boolean'?v.type==='BOOLEAN':true).map(v=>({value:v.name,label:v.name+' · '+v.type.toLowerCase()}))];
        if(key==='operator')choices=[{value:'eq',label:'Equal to'},{value:'ne',label:'Not equal to'},{value:'gt',label:'Greater than'},{value:'ge',label:'At least'},{value:'lt',label:'Less than'},{value:'le',label:'At most'}];
        if(type==='BOOLEAN')choices=['true','false'];
        field(s,key==='ticks'?'Wait (ticks · 20 = 1 second)':title(key),node.settings[key]?.value??'',raw=>update(key,type,raw),choices,type==='NUMBER'?'number':'text');
      }
      for(const [port,type] of Object.entries(spec.inputs)){if(type==='EXECUTION')continue;const edge=L.graph(this.doc()).edges.find(e=>e.to===node.id&&e.input===port);
        if(edge){s.append(el('p','hint',title(port)+' comes from a connected '+type.toLowerCase()+' value.'),button('Disconnect '+port,()=>this.change('Disconnect value',d=>{const g=L.ensure(d);g.edges=g.edges.filter(e=>!(e.to===node.id&&e.input===port));g.nodes.find(n=>n.id===node.id).settings[port]??=L.value(type);})));}
        else field(s,title(port)+' · '+type.toLowerCase(),node.settings[port]?.value??'',raw=>update(port,type,raw),type==='BOOLEAN'?['true','false']:null,type==='NUMBER'?'number':type==='STRING'?'textarea':'text');
      }
      if(node.type==='delay')s.append(el('p','hint','1–1200 ticks. Closing or replacing the interface cancels the continuation.'));
      if(['set_text','set_visible','set_opacity'].includes(node.type))s.append(el('p','hint','SCREEN supports up to 16 component layers, opacity in eighths, and bounded bitmap text. Inventory uses native labels and visibility. HUD requires a static design.'));
      if(node.type==='player_command')s.append(el('p','hint','Without a leading /. Runs with the player’s permissions.'));
      return;
    }
    const s=section('Session variables');s.append(el('p','hint','Each open interface starts with its own values. Closing discards them.'));
    for(const v of L.graph(this.doc()).variables){const row=el('div','logic-variable');row.append(el('strong','',v.name+' · '+v.type.toLowerCase()));field(row,'Initial '+v.name,v.initial.value,raw=>this.change('Set initial variable',d=>{if(v.type==='NUMBER'&&!Number.isFinite(Number(raw)))throw new Error('Enter a finite number.');L.graph(d).variables.find(i=>i.name===v.name).initial=L.value(v.type,raw);}),v.type==='BOOLEAN'?['true','false']:null,v.type==='NUMBER'?'number':'text');row.append(button('Remove',()=>this.change('Remove variable',d=>{if(L.graph(d).nodes.some(n=>n.settings.variable?.value===v.name))throw new Error('Remove this variable’s node references first.');L.ensure(d).variables=L.graph(d).variables.filter(i=>i.name!==v.name);})));s.append(row);}
    const name=field(s,'Variable name','',()=>{}),type=field(s,'Variable type','STRING',()=>{},['STRING','NUMBER','BOOLEAN']);
    s.append(button('Add variable',()=>this.change('Add session variable',d=>{const g=L.ensure(d),id=name.value.trim();if(!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(id)||g.variables.some(v=>v.name===id))throw new Error('Choose a unique variable name using lowercase letters, numbers, hyphens or underscores.');if(g.variables.length>=32)throw new Error('Up to 32 variables are supported.');g.variables.push({name:id,type:type.value,initial:L.value(type.value)});})));s.append(el('p','hint','Connect matching ports. Click a connection to remove it. Shift-select nodes to copy or move them together.'));
  }
}
