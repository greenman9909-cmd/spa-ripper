
document.querySelectorAll('[data-menu]').forEach(b=>b.addEventListener('click',()=>document.body.classList.toggle('nav-open')));
document.addEventListener('click',e=>{if(document.body.classList.contains('nav-open')&&!e.target.closest('.sidebar')&&!e.target.closest('[data-menu]'))document.body.classList.remove('nav-open')});
const io='IntersectionObserver'in window?new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting){e.target.classList.add('show');io.unobserve(e.target)}}),{threshold:.08}):null;
document.querySelectorAll('.reveal').forEach(el=>io?io.observe(el):el.classList.add('show'));
