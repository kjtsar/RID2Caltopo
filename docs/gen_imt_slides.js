"use strict";
const pptxgen = require("/opt/homebrew/lib/node_modules/pptxgenjs");

const pres = new pptxgen();
pres.layout = 'LAYOUT_16x9'; // 10" x 5.625"
pres.title = 'SAR Drone Team: IMT Integration Guide';
pres.author = 'SAR Drone Team';

const C = {
  navy:    '1B3A6B',
  navyDk:  '0F2240',
  orange:  'E8611A',
  orangeLt:'F0A070',
  sky:     '2E86AB',
  skyDk:   '1A6A8A',
  white:   'FFFFFF',
  offWhite:'F3F5F8',
  ltGray:  'DDE3EB',
  mdGray:  '8898AA',
  dkGray:  '3D4F61',
  black:   '1A1A2E',
  green:   '1A7A3C',
  greenDk: '145E2E',
  greenLt: 'EAF5EE',
  red:     'B03030',
  redDk:   '882020',
  redLt:   'FBF0F0',
  amber:   'B45309',
  amberLt: 'FEF3C7',
};

const mk = () => ({ type:'outer', color:'000000', blur:6, offset:2, angle:135, opacity:0.12 });

function hdr(slide, title) {
  slide.addShape(pres.shapes.RECTANGLE, {x:0,y:0,w:10,h:0.70,fill:{color:C.navy},line:{color:C.navy}});
  slide.addShape(pres.shapes.RECTANGLE, {x:0,y:0,w:0.20,h:0.70,fill:{color:C.orange},line:{color:C.orange}});
  slide.addText(title, {x:0.30,y:0,w:9.5,h:0.70,fontSize:20,fontFace:'Trebuchet MS',bold:true,color:C.white,valign:'middle',margin:0});
}

function pg(slide, n) {
  slide.addText(`${n} / 9`, {x:9.3,y:5.38,w:0.6,h:0.18,fontSize:9,color:C.mdGray,align:'right',margin:0});
}

// ── SLIDE 1: TITLE ───────────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.navyDk };
  s.addShape(pres.shapes.RECTANGLE, {x:0,y:0,w:10,h:0.22,fill:{color:C.orange},line:{color:C.orange}});
  s.addShape(pres.shapes.RECTANGLE, {x:0,y:5.405,w:10,h:0.22,fill:{color:C.orange},line:{color:C.orange}});

  s.addText('SAR Drone Team', {
    x:0.6,y:0.85,w:8.8,h:1.3,fontSize:52,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle'
  });
  s.addText('IMT Integration Guide', {
    x:0.6,y:2.2,w:8.8,h:0.75,fontSize:28,fontFace:'Trebuchet MS',color:C.orangeLt,align:'center',valign:'middle'
  });
  s.addShape(pres.shapes.RECTANGLE, {x:2.0,y:3.1,w:6.0,h:0.04,fill:{color:C.mdGray},line:{color:C.mdGray}});
  s.addText('Capabilities  ·  Deployment  ·  Coordination', {
    x:0.6,y:3.2,w:8.8,h:0.42,fontSize:16,fontFace:'Calibri',color:C.mdGray,align:'center'
  });
  s.addText('For Duty Officers and Planning Section', {
    x:0.6,y:3.75,w:8.8,h:0.38,fontSize:14,fontFace:'Calibri',italic:true,color:C.orangeLt,align:'center'
  });
  s.addNotes('This guide is intended for the Duty Officer and Planning Section. It covers what the drone team can do, when to activate us, what we need from you, and how we integrate with your Caltopo incident map.');
}

// ── SLIDE 2: CAPABILITIES ────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'What the Drone Team Brings to Your Operation');
  pg(s, 2);

  const caps = [
    {
      icon:'◎', color:C.navy,
      title:'Extended Area Coverage',
      body:'Our BVLOS waiver allows each pilot to search up to 1 mile from their launch point — the equivalent of a full search segment. We cover ground quickly in the early op period.',
    },{
      icon:'◑', color:C.sky,
      title:'Day & Night · All-Weather',
      body:'Thermal IR + visual cameras + spotlight support 24-hour operations. Thermal sees through foliage — useful even in daylight. The Matrice 4TD flies in light rain and snow.',
    },{
      icon:'◈', color:C.green,
      title:'AI-Assisted Detection',
      body:'Eagle Eyes Pilot flags anomalies automatically during flight, reducing fatigue on large-area searches. Eagle Eyes Scan can analyze photos from automated grid flights post-mission.',
    },{
      icon:'◉', color:C.orange,
      title:'Live Tracks in Caltopo',
      body:'RID2Caltopo pushes drone positions directly into your incident map in real time. Planning and IC see exactly where drones are and where has been searched — no separate reporting needed.',
    },
  ];

  caps.forEach((cap, i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const cx = 0.35 + col * 4.9;
    const cy = 0.85 + row * 2.35;
    const cw = 4.6, ch = 2.18;

    s.addShape(pres.shapes.RECTANGLE, {x:cx,y:cy,w:cw,h:ch,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:cx,y:cy,w:cw,h:0.50,fill:{color:cap.color},line:{color:cap.color}});
    s.addText(`${cap.icon}  ${cap.title}`, {
      x:cx+0.15,y:cy,w:cw-0.3,h:0.50,
      fontSize:14,fontFace:'Trebuchet MS',bold:true,color:C.white,valign:'middle',margin:0
    });
    s.addText(cap.body, {
      x:cx+0.18,y:cy+0.58,w:cw-0.36,h:ch-0.68,
      fontSize:13,fontFace:'Calibri',color:C.black,valign:'top',margin:0
    });
  });

  s.addNotes('Eagle Eyes Pilot is real-time AI assistance during flight on the M4TD. Eagle Eyes Scan is a separate post-processing analysis of photos taken during automated grid flights. Both reduce the chance of missing a subject on a large area search.');
}

// ── SLIDE 3: WHEN TO DEPLOY ──────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'When to Activate: Deployment by Scenario');
  pg(s, 3);

  const scenarios = [
    {
      color: C.sky, label:'HASTY LINE  /  ROUTE',
      when:'Early op period',
      what:'Fly both directions along a road, trail, or linear feature. 1-mile radius per pilot. Fast initial coverage along likely travel routes.',
      provide:'Feature name or GPS endpoints · Search direction · Subject description',
    },{
      color: C.green, label:'HASTY POINT  /  AREA',
      when:'Early to mid op period',
      what:'Focused wandering search around a known LKP, clue location, or uncertainty area. Can purposefully cover a specific point or polygon.',
      provide:'GPS center point or area polygon on Caltopo · Subject description and last known clothing',
    },{
      color: C.navy, label:'METHODICAL GRID',
      when:'Mid to late op period',
      what:'Automated grid pattern with periodic photos. Creates a searchable photo mosaic. Eagle Eyes Scan analysis available for thorough review. Best after hasty has been cleared.',
      provide:'Area polygon drawn in Caltopo · We download and fly automatically',
    },
  ];

  const sw = 3.0, sh = 4.42, sy = 0.85;
  scenarios.forEach((sc, i) => {
    const sx = 0.35 + i * 3.22;
    s.addShape(pres.shapes.RECTANGLE, {x:sx,y:sy,w:sw,h:sh,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:sx,y:sy,w:sw,h:0.58,fill:{color:sc.color},line:{color:sc.color}});
    s.addText(sc.label, {x:sx+0.1,y:sy,w:sw-0.2,h:0.58,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});

    s.addText('BEST TIMING', {x:sx+0.12,y:sy+0.65,w:sw-0.24,h:0.22,fontSize:9,fontFace:'Trebuchet MS',bold:true,color:sc.color,margin:0});
    s.addText(sc.when, {x:sx+0.12,y:sy+0.84,w:sw-0.24,h:0.25,fontSize:12,fontFace:'Calibri',bold:true,color:C.black,margin:0});

    s.addShape(pres.shapes.RECTANGLE, {x:sx+0.12,y:sy+1.14,w:sw-0.24,h:0.02,fill:{color:C.ltGray},line:{color:C.ltGray}});

    s.addText('WHAT WE DO', {x:sx+0.12,y:sy+1.22,w:sw-0.24,h:0.22,fontSize:9,fontFace:'Trebuchet MS',bold:true,color:sc.color,margin:0});
    s.addText(sc.what, {x:sx+0.12,y:sy+1.42,w:sw-0.24,h:1.38,fontSize:11.5,fontFace:'Calibri',color:C.black,valign:'top',margin:0});

    s.addShape(pres.shapes.RECTANGLE, {x:sx+0.12,y:sy+2.85,w:sw-0.24,h:0.02,fill:{color:C.ltGray},line:{color:C.ltGray}});

    s.addText('PROVIDE IN ASSIGNMENT', {x:sx+0.12,y:sy+2.93,w:sw-0.24,h:0.22,fontSize:9,fontFace:'Trebuchet MS',bold:true,color:sc.color,margin:0});
    s.addText(sc.provide, {x:sx+0.12,y:sy+3.13,w:sw-0.24,h:1.15,fontSize:11,fontFace:'Calibri',color:C.dkGray,valign:'top',margin:0});
  });

  s.addNotes("Drone assignments can be written on ICS-204 like any other field assignment. For grid search, draw the polygon on Caltopo and we download it directly — no need to hand-transcribe coordinates.");
}

// ── SLIDE 4: MUTUAL AID INTEGRATION ─────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Multi-Team Operations: Mutual Aid Drone Integration');
  pg(s, 4);

  // Value-prop banner
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:0.82,w:9.3,h:0.58,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('When multiple drone teams are on scene, RID2Caltopo gives IC a single unified view of all drone coverage — regardless of which organization is flying.', {
    x:0.50,y:0.82,w:9.0,h:0.58,fontSize:12.5,fontFace:'Calibri',color:C.white,valign:'middle',margin:0
  });

  // Left card: Unified picture
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.55,w:4.45,h:3.75,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.55,w:4.45,h:0.48,fill:{color:C.sky},line:{color:C.sky}});
  s.addText('Single Map — All Teams', {x:0.35,y:1.55,w:4.45,h:0.48,fontSize:14,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addText([
    {text:'Each MA drone team runs RID2Caltopo on their own device',options:{bullet:true,breakLine:true}},
    {text:'All teams\' tracks appear together in a single "Live Drone Tracks" folder in the incident Caltopo map',options:{bullet:true,breakLine:true}},
    {text:'IC and Planning see coverage from every team in real time — one map, one picture',options:{bullet:true,breakLine:true}},
    {text:'Clue reports from all teams post to the same map with team callsign labels',options:{bullet:true,breakLine:true}},
    {text:'r2c-tracker ensures no duplicate tracks even when two zones detect the same drone',options:{bullet:true}},
  ], {x:0.48,y:2.10,w:4.20,h:3.10,fontSize:12.5,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:7});

  // Right card: Loaner program
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:1.55,w:4.45,h:3.75,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:1.55,w:4.45,h:0.48,fill:{color:C.orange},line:{color:C.orange}});
  s.addText('Loaner Equipment for MA Partners', {x:5.2,y:1.55,w:4.45,h:0.48,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addText([
    {text:'We maintain ready-to-deploy packages: RID2Caltopo tablet + Dronescout bridge',options:{bullet:true,breakLine:true}},
    {text:'MA teams without their own setup can be tracking drones within minutes of arrival',options:{bullet:true,breakLine:true}},
    {text:'Compatible with any FAA-compliant drone — works with DJI, Autel, Skydio, and others',options:{bullet:true,breakLine:true}},
    {text:'MA configuration loaded via QR code scan — no manual setup or internet account needed',options:{bullet:true,breakLine:true}},
    {text:'Coordinate loaner allocation with Drone Team Lead at Op start',options:{bullet:true}},
  ], {x:5.33,y:2.10,w:4.20,h:3.10,fontSize:12.5,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:7});

  // Bottom timing note
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:5.15,w:9.3,h:0.30,fill:{color:C.navyDk},line:{color:C.navyDk}});
  s.addText('Tip: Confirm loaner availability when requesting MA drone resources so equipment is staged and config is ready on arrival.', {
    x:0.45,y:5.15,w:9.1,h:0.30,fontSize:10,fontFace:'Calibri',italic:true,color:C.orangeLt,valign:'middle',margin:0
  });

  s.addNotes("We can loan out RID2Caltopo tablets and Dronescout bridges to MA drone teams. The MA config (map credentials, drone callsigns) is shared via QR code on arrival — takes about 5 minutes to set up. Recommend flagging this need when placing the MA request so we can have equipment staged.");
}

// ── SLIDE 5: REQUIREMENTS ────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'What We Need from You');
  pg(s, 5);

  // Communications — critical, full-width warning card
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:0.85,w:9.3,h:1.35,fill:{color:C.amberLt},line:{color:C.amber},shadow:mk()});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:0.85,w:0.10,h:1.35,fill:{color:C.amber},line:{color:C.amber}});
  s.addText('⚠  COMMUNICATIONS — MANDATORY', {
    x:0.55,y:0.88,w:9.0,h:0.30,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.amber,margin:0
  });
  s.addText('Direct voice comms with IC are required before any drone goes airborne. IC must be able to get our drones on the ground immediately if manned air resources are inbound. Assign us Calcord and Dispatch on our radios. Redundant comms (phone or satellite) are strongly preferred.',{
    x:0.55,y:1.18,w:9.0,h:0.90,fontSize:12.5,fontFace:'Calibri',color:C.black,valign:'top',margin:0
  });

  // Three requirement cards below
  const reqs = [
    {
      color: C.navy, icon:'▲', title:'Launch Site',
      body:'Driveable location with overlook of the search area. We need vehicle access to recharge batteries and maintain comms. High points extend our effective range significantly.\n\nNote: terrain or dense trees between the controller and drone limits our effective radius.',
    },{
      color: C.sky, icon:'⊞', title:'Caltopo Map Access',
      body:'Share your incident map ID and grant us write access. We populate live tracks and clue reports directly. We can see your segment boundaries and assignment polygons — no separate spatial briefing needed.',
    },{
      color: C.green, icon:'◫', title:'Clear Assignment',
      body:'Boundaries and search objective for each assignment. Subject description, last known clothing, and any known hazards. For grid search: polygon drawn in Caltopo (we download directly).',
    },
  ];

  const rw = 2.9, rh = 3.0, ry = 2.38;
  reqs.forEach((r, i) => {
    const rx = 0.35 + i * 3.22;
    s.addShape(pres.shapes.RECTANGLE, {x:rx,y:ry,w:rw,h:rh,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:rx,y:ry,w:rw,h:0.48,fill:{color:r.color},line:{color:r.color}});
    s.addText(`${r.icon}  ${r.title}`, {
      x:rx+0.12,y:ry,w:rw-0.24,h:0.48,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.white,valign:'middle',margin:0
    });
    s.addText(r.body, {
      x:rx+0.15,y:ry+0.56,w:rw-0.30,h:rh-0.66,fontSize:12,fontFace:'Calibri',color:C.black,valign:'top',margin:0
    });
  });

  s.addNotes("The comms requirement is non-negotiable for safety. If manned air (helicopter) is activated while our drones are flying, IC must be able to reach us immediately to get drones on the ground. Calcord is the standard coordination channel.");
}

// ── SLIDE 6: GO / NO-GO ──────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Go / No-Go: Weather & Conditions');
  pg(s, 6);

  // GO column
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:0.85,w:4.45,h:0.48,fill:{color:C.green},line:{color:C.green}});
  s.addText('✔  GO', {x:0.35,y:0.85,w:4.45,h:0.48,fontSize:16,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.33,w:4.45,h:3.88,fill:{color:C.greenLt},line:{color:C.green}});

  const goItems = [
    'Visibility ≥ 3 statute miles',
    'Cloud clearance: 500\' below, 2,000\' horizontal',
    'Light rain or snow (Matrice 4TD)',
    'Night ops with thermal payload',
    'Daytime — thermal adds value even in clear weather by seeing through foliage',
    'Manned air either not active or coordinated with Helibase',
  ];
  goItems.forEach((item, i) => {
    const iy = 1.42 + i * 0.56;
    s.addShape(pres.shapes.OVAL, {x:0.50,y:iy+0.05,w:0.20,h:0.20,fill:{color:C.green},line:{color:C.green}});
    s.addText(item, {x:0.78,y:iy,w:3.90,h:0.48,fontSize:12.5,fontFace:'Calibri',color:C.black,valign:'middle',margin:0});
  });

  // NO-GO column
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:0.85,w:4.45,h:0.48,fill:{color:C.red},line:{color:C.red}});
  s.addText('✘  NO-GO', {x:5.2,y:0.85,w:4.45,h:0.48,fontSize:16,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:1.33,w:4.45,h:3.88,fill:{color:C.redLt},line:{color:C.red}});

  const noGoItems = [
    'Visibility < 3 statute miles',
    'Active manned air without Helibase coordination',
    'No direct voice comms with IC established',
    'No accessible launch site within range of the search area',
    'Winds exceeding aircraft limits',
    'Hill, ridge, or dense canopy blocking line of sight between controller and search area',
  ];
  noGoItems.forEach((item, i) => {
    const iy = 1.42 + i * 0.56;
    s.addShape(pres.shapes.RECTANGLE, {x:5.33,y:iy+0.10,w:0.18,h:0.18,fill:{color:C.red},line:{color:C.red}});
    s.addText(item, {x:5.60,y:iy,w:3.90,h:0.48,fontSize:12.5,fontFace:'Calibri',color:C.black,valign:'middle',margin:0});
  });

  s.addNotes("When in doubt, call us. We can often identify a workaround — a different launch site, a different flight altitude, or a partial coverage approach. We would rather be consulted early than told 'we thought it wouldn't work.'");
}

// ── SLIDE 7: WRITING ASSIGNMENTS ─────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Planning: Writing Effective Drone Assignments');
  pg(s, 7);

  // Intro line
  s.addText('Drone assignments go on ICS-204 like any field assignment. Here\'s what makes each type effective:', {
    x:0.35,y:0.80,w:9.3,h:0.32,fontSize:12.5,fontFace:'Calibri',italic:true,color:C.dkGray,margin:0
  });

  const asgn = [
    {
      color:C.sky, label:'HASTY LINE / ROUTE',
      rows:[
        ['Feature',      'Road, trail, or linear feature by name or GPS endpoints'],
        ['Segment size',  'Max 1 mile per pilot — plan multiple segments for longer features'],
        ['Direction',    'Both directions from a central launch point is most efficient'],
        ['Objective',    'Subject description · Likely travel direction · Clue types'],
      ]
    },{
      color:C.green, label:'HASTY POINT / AREA',
      rows:[
        ['Location',      'GPS center point or area polygon drawn on Caltopo'],
        ['Priority',      'Inner-to-outer from LKP or clue location'],
        ['Objective',     'Subject description · Last known clothing · Urgency level'],
        ['Note',          'We can loiter and recheck a specific point on IC request'],
      ]
    },{
      color:C.navy, label:'METHODICAL GRID',
      rows:[
        ['Boundary',     'Polygon drawn in Caltopo — we download directly to the drone'],
        ['Clearance',    'Note terrain obstacles; 200m+ clearance from ridge lines preferred'],
        ['Output',       'Photo mosaic and/or Eagle Eyes Scan analysis of captured images'],
        ['Timing',       'Best after hasty clears the segment — adds coverage, not a guarantee'],
      ]
    },
  ];

  const aw = 2.9, ay = 1.22;
  asgn.forEach((a, i) => {
    const ax = 0.35 + i * 3.22;
    const ah = 4.18;
    s.addShape(pres.shapes.RECTANGLE, {x:ax,y:ay,w:aw,h:ah,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:ax,y:ay,w:aw,h:0.45,fill:{color:a.color},line:{color:a.color}});
    s.addText(a.label, {x:ax+0.1,y:ay,w:aw-0.2,h:0.45,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});

    let ry = ay + 0.52;
    a.rows.forEach(([field, desc]) => {
      s.addText(field, {x:ax+0.12,y:ry,w:0.82,h:0.22,fontSize:10,fontFace:'Trebuchet MS',bold:true,color:a.color,margin:0});
      s.addText(desc,  {x:ax+0.12,y:ry+0.20,w:aw-0.24,h:0.58,fontSize:11,fontFace:'Calibri',color:C.black,valign:'top',margin:0});
      ry += 0.85;
    });
  });

  s.addNotes("For grid search, the easiest workflow: Planning draws the polygon on Caltopo, notifies drone team lead of the assignment, and we handle the rest. No coordinate transcription needed.");
}

// ── SLIDE 8: CALTOPO INTEGRATION ─────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Real-Time Visibility: What You See in Caltopo');
  pg(s, 8);

  // Two columns: Planning/IC view (left) and After the Op (right)
  // Left: what appears on the map
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:0.85,w:5.5,h:0.45,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('During the Operation', {x:0.35,y:0.85,w:5.5,h:0.45,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.30,w:5.5,h:4.05,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});

  const during = [
    ['Live Drone Tracks folder', '"Live Drone Tracks" appears automatically on the incident map — no setup required on the Planning or IC side.'],
    ['Per-drone tracks', 'Each drone shows as a moving labeled track. Callsign matches your assignment roster.'],
    ['Assignment coverage', 'You can see in real time which parts of an assignment have been flown — helps Planning redirect if a segment clears faster than expected.'],
    ['Clue markers', 'Pilots post clues directly to the map during the flight. Appears as a marker with notes and time.'],
  ];

  let dy = 1.38;
  during.forEach(([title, desc]) => {
    s.addShape(pres.shapes.RECTANGLE, {x:0.43,y:dy,w:0.06,h:0.22,fill:{color:C.orange},line:{color:C.orange}});
    s.addText(title, {x:0.56,y:dy,w:5.18,h:0.24,fontSize:12.5,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(desc,  {x:0.56,y:dy+0.24,w:5.18,h:0.55,fontSize:11.5,fontFace:'Calibri',color:C.black,margin:0});
    dy += 0.9;
  });

  // Right: after the op
  s.addShape(pres.shapes.RECTANGLE, {x:6.15,y:0.85,w:3.5,h:0.45,fill:{color:C.dkGray},line:{color:C.dkGray}});
  s.addText('After the Operation', {x:6.15,y:0.85,w:3.5,h:0.45,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:6.15,y:1.30,w:3.5,h:4.05,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});

  const after = [
    ['Flight track logs', 'Complete track logs available for ICS-214 and after-action review.'],
    ['Coverage map', 'Persistent track history shows exactly what ground was covered and when — useful for congruency review.'],
    ['Clue documentation', 'All clue markers remain on the map with timestamp and pilot notes.'],
    ['Analytics upload', 'Flight hours, weather context, and pilot stats uploaded to r2c-tracker for operational records.'],
  ];

  let ay2 = 1.38;
  after.forEach(([title, desc]) => {
    s.addShape(pres.shapes.RECTANGLE, {x:6.22,y:ay2,w:0.06,h:0.22,fill:{color:C.sky},line:{color:C.sky}});
    s.addText(title, {x:6.35,y:ay2,w:3.20,h:0.24,fontSize:12.5,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(desc,  {x:6.35,y:ay2+0.24,w:3.20,h:0.55,fontSize:11.5,fontFace:'Calibri',color:C.black,margin:0});
    ay2 += 0.9;
  });

  s.addNotes("Planning can monitor drone coverage in real time and redirect assignments on the fly. If a hasty line clears faster than expected, the drone team can immediately roll to the next segment without waiting for a formal debrief.");
}

// ── SLIDE 9: DUTY OFFICER CHECKLIST ──────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Duty Officer: Activating the Drone Team');
  pg(s, 9);

  const sections = [
    {
      label:'CONSIDER ACTIVATION WHEN…', color:C.navy, items:[
        'Rapid hasty coverage of roads, trails, or a large area is needed',
        'Night search or poor visibility for ground teams (thermal advantage)',
        'Terrain is dangerous or difficult for searchers on foot (cliffs, steep canyons)',
        'A specific point or clue needs close aerial inspection',
      ]
    },{
      label:'BEFORE ACTIVATING', color:C.sky, items:[
        'Confirm a driveable launch site exists with overlook of the search area',
        'Check airspace: active TFRs, proximity to airport, heli operations',
        'Ensure direct comms channel is available for IC ↔ drone team',
      ]
    },{
      label:'WHEN ACTIVATING', color:C.green, items:[
        'Contact Drone Team Lead — provide incident map ID and subject summary',
        'Assign radio channels: Calcord + Dispatch (+ incident tac if available)',
        'Provide redundant comms contact (phone or satellite)',
        'Confirm no manned air is immediately inbound',
      ]
    },{
      label:'BRIEF YOUR IC', color:C.orange, items:[
        'Drones will be airborne — live tracks visible in Caltopo automatically',
        'IC must maintain comms with drone team and can land drones immediately',
        'Drone team operates under IC authority and reports clues directly to the map',
      ]
    },
  ];

  const sw = 4.45, sy = 0.85;
  sections.forEach((sec, i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const sx = 0.35 + col * 4.9;
    const secY = sy + row * 2.38;
    const sh = 2.18;

    s.addShape(pres.shapes.RECTANGLE, {x:sx,y:secY,w:sw,h:sh,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:sx,y:secY,w:sw,h:0.40,fill:{color:sec.color},line:{color:sec.color}});
    s.addText(sec.label, {
      x:sx+0.12,y:secY,w:sw-0.24,h:0.40,
      fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.white,valign:'middle',margin:0
    });

    const itemText = sec.items.map((item, ii) => ({
      text: item,
      options: { bullet:true, breakLine: ii < sec.items.length - 1 }
    }));
    s.addText(itemText, {
      x:sx+0.15,y:secY+0.46,w:sw-0.30,h:sh-0.56,
      fontSize:11.5,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:5
    });
  });

  s.addNotes("The most critical step that is most often missed: briefing IC that drones are airborne and that IC must be able to reach the drone team immediately. This is a safety requirement, not a preference.");
}

// ── WRITE FILE ────────────────────────────────────────────────────────────────
pres.writeFile({ fileName: '/Users/kjt/Projects/RID2Caltopo/docs/IMT_Drone_Integration_Guide.pptx' })
  .then(() => console.log('DONE'))
  .catch(err => { console.error('ERROR:', err); process.exit(1); });
