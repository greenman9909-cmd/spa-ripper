
document.querySelectorAll('[data-menu]').forEach(b=>b.addEventListener('click',()=>document.body.classList.toggle('nav-open')));
document.addEventListener('click',e=>{if(document.body.classList.contains('nav-open')&&!e.target.closest('.sidebar')&&!e.target.closest('[data-menu]'))document.body.classList.remove('nav-open')});
const io='IntersectionObserver'in window?new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting){e.target.classList.add('show');io.unobserve(e.target)}}),{threshold:.08}):null;
document.querySelectorAll('.reveal').forEach(el=>io?io.observe(el):el.classList.add('show'));

(function(){
  const path = location.pathname;
  const $ = (s, root=document) => root.querySelector(s);
  const $$ = (s, root=document) => [...root.querySelectorAll(s)];
  const json = async (url, options={}) => {
    const r = await fetch(url, options);
    let d = {};
    try { d = await r.json(); } catch {}
    if (!r.ok) throw Object.assign(new Error(d.error || "Request failed."), {status:r.status, data:d});
    return d;
  };
  const apiPost = (url, body={}) => json(url, {
    method:"POST",
    headers:{"Content-Type":"application/json"},
    body:JSON.stringify(body)
  });
  const apiPatch = (url, body={}) => json(url, {
    method:"PATCH",
    headers:{"Content-Type":"application/json"},
    body:JSON.stringify(body)
  });
  const fmtBytes = n => {
    const v = Number(n||0);
    if (v < 1024) return v+" B";
    if (v < 1048576) return (v/1024).toFixed(1)+" KB";
    if (v < 1073741824) return (v/1048576).toFixed(1)+" MB";
    return (v/1073741824).toFixed(1)+" GB";
  };
  const hostOf = url => { try { return new URL(url).hostname; } catch { return url; } };
  const goSignin = () => { location.href = "/signin?next=" + encodeURIComponent(path + location.search); };

  async function hydrateUser(){
    try{
      const d = await json("/api/me");
      if(!d.authenticated){
        if(/^\/(dashboard|new|project|billing|account|settings|admin)/.test(path)) goSignin();
        return null;
      }
      const u=d.user||{};
      $$(".plan-chip b").forEach(el=>el.textContent=u.role==="owner"?"Owner access":u.plan==="pro"?"Pro plan":"Free plan");
      $$(".plan-chip p").forEach(el=>el.textContent=u.role==="owner"?"Unlimited captures + private tools.":u.plan==="pro"?"Unlimited captures enabled.":u.free_capture_used?"Free capture used.":"One complete capture included.");
      $$(".avatar").forEach(el=>{
        const email=(u.email||"WL").trim();
        el.textContent=(email.slice(0,2)||"WL").toUpperCase();
      });
      if(u.role==="owner" && !$('.side-nav a[href="/admin"]')){
        $(".side-nav").slice(-1)[0]?.insertAdjacentHTML("beforeend", '<a href="/admin"><svg viewBox="0 0 24 24"><path d="M4 5h16v14H4zM8 9h8M8 13h5"/></svg>Owner console</a>');
      }
      return u;
    }catch(e){ return null; }
  }

  function authPage(mode){
    const form=$(".auth-form");
    if(!form) return;
    const button=$("button",form);
    const email=$('input[type="email"]',form);
    const secret=$('input[type="password"]',form);
    if(!button||!email||!secret) return;
    let note=document.createElement("p");
    note.className="auth-error";
    note.setAttribute("role","alert");
    button.insertAdjacentElement("afterend",note);
    const run=async()=>{
      note.textContent="";
      const label=button.textContent;
      button.disabled=true;
      button.textContent=mode==="signin"?"Signing in…":"Creating account…";
      try{
        const d=await apiPost("/api/auth/"+mode,{email:email.value.trim(),password:secret.value});
        if(d.confirmation_required){
          note.classList.add("ok");
          note.textContent="Check your email to confirm your account.";
          button.textContent="Email sent";
          return;
        }
        const next=new URLSearchParams(location.search).get("next");
        location.href=next||d.redirect||"/dashboard";
      }catch(e){
        note.classList.remove("ok");
        note.textContent=e.message;
        button.disabled=false;
        button.textContent=label;
      }
    };
    button.addEventListener("click",run);
    form.addEventListener("submit",e=>{e.preventDefault();run();});
  }

  async function dashboard(){
    if(path!=="/dashboard") return;
    const u=await hydrateUser();
    if(!u) return;
    try{
      const d=await json("/api/projects");
      const projects=d.projects||[];
      const metrics=$$(".metric-value");
      if(metrics[0]) metrics[0].textContent=u.role==="owner"?"Owner":u.plan==="pro"?"Pro":"Free";
      if(metrics[1]) metrics[1].textContent=String(projects.filter(p=>p.status==="done").length);
      if(metrics[2]) metrics[2].textContent=fmtBytes(projects.reduce((n,p)=>n+Number(p.byte_count||0),0));
      const empty=$(".card.empty");
      const head=$(".section-head");
      if(projects.length && empty){
        empty.classList.remove("empty");
        empty.innerHTML='<div class="project-list"></div>';
        const list=$(".project-list",empty);
        projects.slice(0,8).forEach(p=>{
          const row=document.createElement("a");
          row.className="project-row";
          row.href="/project/"+p.id;
          row.innerHTML='<div class="project-favicon">'+hostOf(p.source_url).slice(0,1).toUpperCase()+'</div><div class="project-row-main"><b>'+hostOf(p.source_url)+'</b><span>'+p.source_url+'</span></div><span class="status-pill '+p.status+'">'+p.status+'</span><span class="project-meta">'+(p.file_count||0)+' files · '+fmtBytes(p.byte_count)+'</span><span class="row-arrow">→</span>';
          list.appendChild(row);
        });
        if(head) $("p",head).textContent=projects.length+" saved project"+(projects.length===1?"":"s");
      }
    }catch(e){}
  }

  async function projectPage(){
    if(!path.startsWith("/project")) return;
    const u=await hydrateUser();
    if(!u) return;
    const id=path.split("/")[2];
    if(!id) return;
    try{
      const d=await json("/api/projects/"+id);
      const p=d.project;
      $(".badge.gray") && ($(".badge.gray").textContent=p.status);
      const h=$(".hero-row h1");
      if(h) h.textContent=hostOf(p.source_url);
      const desc=$(".hero-row p");
      if(desc) desc.textContent=p.source_url;
      const box=$(".preview-box");
      if(box){
        if(p.status==="done"){
          box.innerHTML='<iframe class="project-preview-frame" title="Captured website preview" src="/preview/'+id+'/"></iframe>';
          const hero=$(".hero-row");
          hero?.insertAdjacentHTML("beforeend",'<div class="project-actions"><a class="btn" target="_blank" rel="noopener" href="/preview/'+id+'/">Open preview ↗</a><a class="btn primary" href="/api/jobs/'+id+'/download">Download ZIP ↓</a></div>');
        }else{
          box.innerHTML='<div class="capture-running"><div class="capture-spinner"></div><h3>'+p.status.charAt(0).toUpperCase()+p.status.slice(1)+' capture</h3><p>WebLoom is building the project. This page updates automatically.</p></div>';
          setTimeout(()=>location.reload(),3500);
        }
      }
    }catch(e){}
  }

  async function billingPage(){
    if(path!=="/billing" && path!=="/pricing") return;
    const u=await hydrateUser();
    const buttons=$$("button");
    buttons.forEach(btn=>{
      if(/upgrade|unlock|get pro/i.test(btn.textContent)){
        btn.addEventListener("click",async()=>{
          const old=btn.textContent;btn.disabled=true;btn.textContent="Opening checkout…";
          try{const d=await apiPost("/api/billing/checkout");location.href=d.url;}
          catch(e){btn.disabled=false;btn.textContent=old;alert(e.message);}
        });
      }
      if(/manage billing/i.test(btn.textContent)){
        btn.addEventListener("click",async()=>{
          try{const d=await apiPost("/api/billing/portal");location.href=d.url;}catch(e){alert(e.message);}
        });
      }
    });
    if(u && path==="/billing"){
      const notice=$(".notice");
      if(notice) notice.textContent=u.role==="owner"?"Owner access is active. Billing is optional for this account.":u.plan==="pro"?"WebLoom Pro is active on this account.":"Your free capture is ready. Upgrade when you need unlimited captures.";
    }
  }

  function recoveryPage(){
    if(path!=="/forgot") return;
    const form=$("#recoverForm");
    const button=$("#recoverButton");
    const email=$("#recoverEmail");
    const status=$("#recoverStatus");
    if(!form||!button||!email||!status) return;
    form.addEventListener("submit",async e=>{
      e.preventDefault();
      status.textContent="";
      const old=button.textContent;
      button.disabled=true;
      button.textContent="Sending…";
      try{
        await apiPost("/api/auth/recover",{email:email.value.trim()});
        status.classList.add("ok");
        status.textContent="Recovery email sent. Check your inbox.";
        button.textContent="Email sent";
      }catch(err){
        status.classList.remove("ok");
        status.textContent=err.message;
        button.disabled=false;
        button.textContent=old;
      }
    });
  }

  async function accountPage(){
    if(path!=="/account") return;
    const u=await hydrateUser();
    if(!u) return;
    const name=$("#accountName"), email=$("#accountEmail"), save=$("#saveAccount"), status=$("#accountStatus");
    if(name) name.value=u.display_name||"";
    if(email) email.value=u.email||"";
    save?.addEventListener("click",async()=>{
      const old=save.textContent;
      save.disabled=true; save.textContent="Saving…";
      if(status) status.textContent="";
      try{
        const d=await apiPatch("/api/account",{display_name:name?.value.trim()||""});
        if(status){status.className="form-status ok";status.textContent="Account saved.";}
        if(d.profile?.display_name && name) name.value=d.profile.display_name;
      }catch(err){
        if(status){status.className="form-status error";status.textContent=err.message;}
      }finally{
        save.disabled=false;save.textContent=old;
      }
    });
  }

  async function settingsPage(){
    if(path!=="/settings") return;
    const u=await hydrateUser();
    if(!u) return;
    const settings=u.settings||{};
    const capture=$("#captureMode"), format=$("#exportFormat"), naming=$("#projectNaming"), save=$("#saveSettings"), status=$("#settingsStatus");
    if(capture) capture.value=settings.capture_mode||"standard";
    if(format) format.value=settings.export_format||"zip";
    if(naming) naming.value=settings.project_naming||"hostname";
    const deepOption=capture?.querySelector('option[value="deep"]');
    if(deepOption && u.role!=="owner" && u.plan!=="pro") deepOption.disabled=true;
    save?.addEventListener("click",async()=>{
      const old=save.textContent;save.disabled=true;save.textContent="Saving…";
      if(status) status.textContent="";
      try{
        await apiPatch("/api/settings",{
          capture_mode:capture?.value||"standard",
          export_format:format?.value||"zip",
          project_naming:naming?.value||"hostname"
        });
        if(status){status.className="form-status ok";status.textContent="Defaults saved.";}
      }catch(err){
        if(status){status.className="form-status error";status.textContent=err.message;}
      }finally{save.disabled=false;save.textContent=old;}
    });
  }

  async function signoutButtons(){
    $$("[data-signout]").forEach(btn=>btn.addEventListener("click",async()=>{await apiPost("/api/auth/signout");location.href="/";}));
  }

  if(path==="/signin") authPage("signin");
  if(path==="/signup") authPage("signup");
  recoveryPage();
  if(path==="/dashboard") dashboard(); else hydrateUser();
  projectPage();
  billingPage();
  accountPage();
  settingsPage();
  signoutButtons();

  let motionFrame=0;
  addEventListener("scroll",()=>{
    if(motionFrame) return;
    motionFrame=requestAnimationFrame(()=>{
      motionFrame=0;
      const y=Math.max(-36,Math.min(72,scrollY*.035));
      document.documentElement.style.setProperty("--app-orb-y",y+"px");
    });
  },{passive:true});
})();