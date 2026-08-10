"use strict";
const pptxgen = require("/opt/homebrew/lib/node_modules/pptxgenjs");

const pres = new pptxgen();
pres.layout = 'LAYOUT_16x9'; // 10" x 5.625"
pres.title = 'Live Drone Tracking for SAR Operations';
pres.author = 'SAR Drone Documentation';

const C = {
  navy:    '1B3A6B',
  navyDk:  '0F2240',
  orange:  'E8611A',
  orangeLt:'F0A070',
  sky:     '2E86AB',
  white:   'FFFFFF',
  offWhite:'F3F5F8',
  ltGray:  'DDE3EB',
  mdGray:  '8898AA',
  dkGray:  '3D4F61',
  black:   '1A1A2E',
  green:   '1A7A3C',
  red:     'B03030',
  yellow:  'CA8A04',
  brown:   '3D2008',
  terrain: '2C1800',
};

const mk = () => ({ type:'outer', color:'000000', blur:6, offset:2, angle:135, opacity:0.12 });

function hdr(slide, title) {
  slide.addShape(pres.shapes.RECTANGLE, {x:0,y:0,w:10,h:0.70,fill:{color:C.navy},line:{color:C.navy}});
  slide.addShape(pres.shapes.RECTANGLE, {x:0,y:0,w:0.20,h:0.70,fill:{color:C.orange},line:{color:C.orange}});
  slide.addText(title, {x:0.30,y:0,w:9.5,h:0.70,fontSize:20,fontFace:'Trebuchet MS',bold:true,color:C.white,valign:'middle',margin:0});
}

function pg(slide, n) {
  slide.addText(`${n} / 16`, {x:9.3,y:5.38,w:0.6,h:0.18,fontSize:9,color:C.mdGray,align:'right',margin:0});
}

// ── SLIDE 1: TITLE ──────────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.navyDk };
  s.addShape(pres.shapes.RECTANGLE, {x:0,y:0,w:10,h:0.22,fill:{color:C.orange},line:{color:C.orange}});
  s.addShape(pres.shapes.RECTANGLE, {x:0,y:5.405,w:10,h:0.22,fill:{color:C.orange},line:{color:C.orange}});
  s.addText('Live Drone Tracking\nfor SAR Operations', {
    x:0.6,y:0.85,w:8.8,h:2.2,
    fontSize:46,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle'
  });
  s.addText('Using RID2Caltopo + r2c-tracker with Caltopo Teams', {
    x:0.6,y:3.2,w:8.8,h:0.65,fontSize:19,fontFace:'Calibri',color:C.orangeLt,align:'center'
  });
  s.addShape(pres.shapes.RECTANGLE, {x:2.0,y:4.05,w:6.0,h:0.03,fill:{color:C.mdGray},line:{color:C.mdGray}});
  s.addText('For SAR drone teams familiar with Caltopo · Not affiliated with DroneSense or Caltopo', {
    x:0.6,y:4.15,w:8.8,h:0.35,fontSize:11,fontFace:'Calibri',italic:true,color:C.mdGray,align:'center'
  });
  s.addNotes('Welcome. This deck walks SAR drone operators through the full workflow of using RID2Caltopo and r2c-tracker to show live drone tracks on a shared Caltopo Teams map.');
}

// ── SLIDE 2: THE PROBLEM ─────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'The Gap Between Planning and Execution');
  pg(s, 2);

  s.addText('The problem today', {x:0.35,y:0.85,w:4.4,h:0.35,fontSize:14,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
  s.addText([
    {text:'You assign drone segments on your Caltopo map',options:{bullet:true,breakLine:true}},
    {text:'But once drones are airborne, your IC has no real-time visibility of where they actually are',options:{bullet:true,breakLine:true}},
    {text:'Coverage gaps and overlaps are invisible until after the flight',options:{bullet:true,breakLine:true}},
    {text:'DroneSense solves this — but costs $$$, requires dedicated hardware, and is out of reach for most volunteer teams',options:{bullet:true}},
  ], {x:0.35,y:1.25,w:4.5,h:3.1,fontSize:13.5,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:8});

  s.addShape(pres.shapes.RECTANGLE, {x:5.1,y:0.85,w:4.55,h:3.55,fill:{color:C.navy},line:{color:C.navy},shadow:mk()});
  s.addText('The solution', {x:5.2,y:0.92,w:4.35,h:0.35,fontSize:14,fontFace:'Trebuchet MS',bold:true,color:C.orangeLt,margin:0});
  s.addText([
    {text:'RID2Caltopo reads the FAA-required Remote ID signal every modern drone already broadcasts',options:{bullet:true,breakLine:true}},
    {text:'No drone modifications needed',options:{bullet:true,breakLine:true}},
    {text:'Live tracks appear on your shared Caltopo map within seconds',options:{bullet:true,breakLine:true}},
    {text:'Your IC and all Caltopo team members see drone positions in real time',options:{bullet:true}},
  ], {x:5.2,y:1.35,w:4.35,h:2.9,fontSize:13,fontFace:'Calibri',color:C.white,valign:'top',paraSpaceAfter:8});

  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:4.6,w:9.3,h:0.78,fill:{color:C.ltGray},line:{color:C.ltGray}});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:4.6,w:0.08,h:0.78,fill:{color:C.orange},line:{color:C.orange}});
  s.addText('Remote ID is the FAA-mandated Bluetooth/WiFi signal all drones sold after September 2023 must broadcast in flight. DJI, Skydio, Autel, and most manufacturers comply.', {
    x:0.52,y:4.6,w:9.05,h:0.78,fontSize:11.5,fontFace:'Calibri',italic:true,color:C.dkGray,valign:'middle',margin:0
  });
  s.addNotes('Remote ID is the FAA-mandated Bluetooth/WiFi broadcast that all drones sold after Sept 2023 must transmit. Every DJI, Skydio, Autel, etc. already does this.');
}

// ── SLIDE 3: HOW IT WORKS ────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'How RID2Caltopo Works');
  pg(s, 3);

  const boxes = [
    { label:'Drone\nin Flight',        sub:'DJI · Skydio · Autel\nany FAA-compliant',              color:C.dkGray },
    { label:'Remote ID\nBroadcast',    sub:'Bluetooth 4/5\nWiFi Beacon / NaN',                     color:C.sky    },
    { label:'Dronescout\nBridge',      sub:'DS110 on tall tripod\n15+ ft elevation',                color:C.navy   },
    { label:'RID2Caltopo\nAndroid App',sub:'Decodes · Labels\nSequences waypoints',                color:C.navy   },
    { label:'Caltopo Teams\nMap',      sub:'Live tracks · All viewers\nReal-time updates',          color:C.orange },
  ];

  const bw=1.55, bh=1.35, gap=0.22;
  const totalW = boxes.length*bw + (boxes.length-1)*gap;
  const startX = (10-totalW)/2;
  const bY = 1.0;

  boxes.forEach((b, i) => {
    const bX = startX + i*(bw+gap);
    s.addShape(pres.shapes.RECTANGLE, {x:bX,y:bY,w:bw,h:bh,fill:{color:b.color},line:{color:b.color},shadow:mk()});
    s.addText(b.label, {x:bX,y:bY+0.08,w:bw,h:0.65,fontSize:12.5,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
    s.addText(b.sub,   {x:bX,y:bY+0.75,w:bw,h:0.55,fontSize:9,fontFace:'Calibri',color:b.color===C.orange?C.navyDk:C.ltGray,align:'center',valign:'top',margin:0});
    if (i < boxes.length-1) {
      const ax = bX+bw;
      s.addShape(pres.shapes.RECTANGLE, {x:ax,y:bY+bh/2-0.03,w:gap-0.04,h:0.06,fill:{color:C.mdGray},line:{color:C.mdGray}});
      s.addText('▶', {x:ax+gap-0.24,y:bY+bh/2-0.15,w:0.24,h:0.3,fontSize:13,color:C.mdGray,align:'center',margin:0});
    }
  });

  s.addText([
    {text:'No modifications to the drone required',options:{bullet:true,breakLine:true}},
    {text:'Works with any FAA-compliant drone (DJI, Skydio, Autel, and others)',options:{bullet:true,breakLine:true}},
    {text:'Live tracks appear on your shared Caltopo map within seconds of the first GPS fix',options:{bullet:true,breakLine:true}},
    {text:'Your IC and all Caltopo team members see drone positions in real time',options:{bullet:true}},
  ], {x:0.5,y:2.6,w:9.0,h:2.75,fontSize:13.5,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:8});

  s.addNotes("The Dronescout Bridge is the critical piece. A phone's built-in Bluetooth only picks up drones within a few dozen feet. The Bridge elevated on a 15+ foot tripod and powered by a USB battery bank can detect drones thousands of feet away.");
}

// ── SLIDE 4: HARDWARE ────────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'What You Need');
  pg(s, 4);

  // Required column
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:0.85,w:4.45,h:0.42,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('REQUIRED', {x:0.35,y:0.85,w:4.45,h:0.42,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.27,w:4.45,h:4.1,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});

  const req = [
    ['Android device',           'Android 11 (API 30) or newer'],
    ['Dronescout Bridge',        'DS110 (tri-band, recommended) for teams with Skydio X10\nDS100 (dual-band, cheaper) for teams running DJI/Autel only'],
    ['Tripod + power bank',      'Mount Bridge 15+ ft high\nUSB power bank for field operation'],
    ['RID2Caltopo app',          'Install from APK or Play Store'],
    ['Caltopo Teams account',    'With API credentials (team_id,\ncredential_id, credential_secret)'],
  ];
  let ry=1.35;
  req.forEach(([title,sub]) => {
    s.addText(title, {x:0.45,y:ry,w:4.2,h:0.24,fontSize:12.5,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(sub,   {x:0.45,y:ry+0.22,w:4.2,h:0.48,fontSize:10.5,fontFace:'Calibri',color:C.dkGray,margin:0});
    ry += 0.78;
  });

  // Optional column
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:0.85,w:4.45,h:0.42,fill:{color:C.sky},line:{color:C.sky}});
  s.addText('RECOMMENDED FOR LARGE OPS', {x:5.2,y:0.85,w:4.45,h:0.42,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:1.27,w:4.45,h:4.1,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});

  const opt = [
    ['r2c-tracker service',     'Centralized multi-zone ownership coordination\n(primary coordination method)'],
    ['MQTT broker (fallback)',   'Free-tier MQTT as backup arbiter\nif r2c-tracker unreachable'],
    ['Starlink Mini + battery',  'For zones without cellular coverage\n(Sierra Nevada, remote canyons)'],
    ['Tall tripod (10–15 ft)',   'Telescoping mast for\nmaximum RF detection range'],
  ];
  let oy=1.35;
  opt.forEach(([title,sub]) => {
    s.addText(title, {x:5.30,y:oy,w:4.2,h:0.24,fontSize:12.5,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(sub,   {x:5.30,y:oy+0.22,w:4.2,h:0.48,fontSize:10.5,fontFace:'Calibri',color:C.dkGray,margin:0});
    oy += 0.88;
  });

  s.addNotes("DS100 is a cheaper single-band option from BlueMark that works well for teams running DJI or Autel drones. DS110 adds 5GHz WiFi and WiNan support needed for Skydio X10 drones.");
}

// ── SLIDE 5: CONFIG FILES ────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Pre-Incident Setup: Two Config Files');
  pg(s, 5);

  s.addText('Your team lead creates these once. New members receive them by scanning a QR code shown in the app.', {
    x:0.35,y:0.82,w:9.3,h:0.35,fontSize:12.5,fontFace:'Calibri',italic:true,color:C.dkGray,margin:0
  });

  // ridmap.json card
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.25,w:4.45,h:4.12,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.25,w:4.45,h:0.5,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('ridmap.json', {x:0.35,y:1.25,w:4.45,h:0.5,fontSize:15,fontFace:'Courier New',bold:true,color:C.orangeLt,align:'center',valign:'middle',margin:0});
  s.addText('Maps hardware Remote IDs → human-readable callsigns', {
    x:0.45,y:1.83,w:4.25,h:0.38,fontSize:12,fontFace:'Calibri',color:C.dkGray,margin:0
  });
  s.addShape(pres.shapes.RECTANGLE, {x:0.45,y:2.27,w:4.25,h:0.72,fill:{color:C.navyDk},line:{color:C.navyDk}});
  s.addText('"1581F6Z9C24BH0036EJL"\n  →  "SAR7-Mini4Pro"', {
    x:0.45,y:2.27,w:4.25,h:0.72,fontSize:11,fontFace:'Courier New',color:C.orangeLt,align:'center',valign:'middle',margin:6
  });
  s.addText([
    {text:'Callsign format: letters + numbers (SAR7, 1SAR7, P16)',options:{bullet:true,breakLine:true}},
    {text:'Include drone model for quick ID at the ops table',options:{bullet:true,breakLine:true}},
    {text:'Update before each incident if adding new aircraft',options:{bullet:true,breakLine:true}},
    {text:'Share with Mutual Aid partners via QR code in the app',options:{bullet:true}},
  ], {x:0.45,y:3.08,w:4.25,h:2.18,fontSize:12,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:8});

  // credentials.json card
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:1.25,w:4.45,h:4.12,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:1.25,w:4.45,h:0.5,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('credentials.json', {x:5.2,y:1.25,w:4.45,h:0.5,fontSize:15,fontFace:'Courier New',bold:true,color:C.orangeLt,align:'center',valign:'middle',margin:0});
  s.addText('Caltopo Teams API keys and map settings', {
    x:5.3,y:1.83,w:4.25,h:0.38,fontSize:12,fontFace:'Calibri',color:C.dkGray,margin:0
  });
  s.addShape(pres.shapes.RECTANGLE, {x:5.3,y:2.27,w:4.25,h:0.72,fill:{color:C.navyDk},line:{color:C.navyDk}});
  s.addText('team_id · credential_id\ncredential_secret · map_id', {
    x:5.3,y:2.27,w:4.25,h:0.72,fontSize:11,fontFace:'Courier New',color:C.orangeLt,align:'center',valign:'middle',margin:6
  });
  s.addText([
    {text:'API credentials found in your Caltopo Teams account settings',options:{bullet:true,breakLine:true}},
    {text:'Requires "write" permission to push waypoints to the map',options:{bullet:true,breakLine:true}},
    {text:'Optional: r2c-tracker URL and API key for multi-zone ops',options:{bullet:true,breakLine:true}},
    {text:'Loaded once — encrypted and stored securely on-device',options:{bullet:true}},
  ], {x:5.3,y:3.08,w:4.25,h:2.18,fontSize:12,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:8});

  s.addNotes("Config files are shared between devices by displaying a QR code in the app menu. New team members or Mutual Aid partners scan it to receive the current config. No cloud account required.");
}

// ── SLIDE 6: STARTUP ─────────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Starting Up: Single Zone Operations');
  pg(s, 6);

  const steps = [
    ['1','Place Bridge on tripod (15+ ft)','Connect to USB power bank. Clear line-of-sight to flight area.'],
    ['2','Launch RID2Caltopo app','Allow Bluetooth and WiFi permissions when prompted.'],
    ['3','Load config files','Menu → Load Config → scan QR code from team lead\'s device, or load from local storage.'],
    ['4','Confirm connection','Header shows your map ID and a Caltopo round-trip time (RTT) in milliseconds.'],
    ['5','Drones launch → tracks appear','Callsigns appear in the list as drones take off. Tracks on Caltopo within seconds.'],
  ];

  let sy=0.88;
  steps.forEach(([num,title,detail]) => {
    s.addShape(pres.shapes.OVAL,      {x:0.35,y:sy,w:0.38,h:0.38,fill:{color:C.orange},line:{color:C.orange}});
    s.addText(num, {x:0.35,y:sy,w:0.38,h:0.38,fontSize:14,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
    s.addText(title,  {x:0.82,y:sy,w:5.9,h:0.24,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(detail, {x:0.82,y:sy+0.22,w:5.9,h:0.35,fontSize:11,fontFace:'Calibri',color:C.dkGray,margin:0});
    if (num !== '5') s.addShape(pres.shapes.RECTANGLE, {x:0.525,y:sy+0.38,w:0.04,h:0.22,fill:{color:C.ltGray},line:{color:C.ltGray}});
    sy += 0.88;
  });

  // IC panel
  s.addShape(pres.shapes.RECTANGLE, {x:7.1,y:0.85,w:2.55,h:4.55,fill:{color:C.navy},line:{color:C.navy},shadow:mk()});
  s.addText('What Your IC Sees', {x:7.1,y:0.85,w:2.55,h:0.42,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.orangeLt,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:7.1,y:1.27,w:2.55,h:0.04,fill:{color:C.orange},line:{color:C.orange}});
  s.addText([
    {text:'"Live Drone Tracks" folder on the shared Caltopo map',options:{bullet:true,breakLine:true}},
    {text:'Each drone as a labeled moving track',options:{bullet:true,breakLine:true}},
    {text:'Callsign matches ops assignments',options:{bullet:true,breakLine:true}},
    {text:'Tracks remain after landing for review',options:{bullet:true}},
  ], {x:7.15,y:1.38,w:2.45,h:3.8,fontSize:11,fontFace:'Calibri',color:C.white,valign:'top',paraSpaceAfter:12});

  s.addNotes("Config QR code: in the app menu, tap 'Share Config' to display a QR code. New devices scan it to receive ridmap.json and credentials.json. If the RTT shows 0 or turns red, check internet connectivity and verify API credentials.");
}

// ── SLIDE 7: APP INTERFACE ───────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'What You See in the App');
  pg(s, 7);

  // Header bar mock
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:0.85,w:9.3,h:0.36,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('HEADER BAR (scroll horizontally)', {x:0.35,y:0.85,w:9.3,h:0.36,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.orangeLt,align:'center',valign:'middle',margin:0});
  const hItems = ['Device name\nApp version','Uptime','Current\nmap ID','Caltopo RTT\n(ms)','Drones in\nridmap','Invalid\nmessages'];
  const iW = 9.3/hItems.length;
  hItems.forEach((item,i) => {
    const bx = 0.35+i*iW;
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:1.21,w:iW,h:0.52,fill:{color:C.white},line:{color:C.ltGray}});
    s.addText(item, {x:bx+0.04,y:1.21,w:iW-0.08,h:0.52,fontSize:9.5,fontFace:'Calibri',color:C.dkGray,align:'center',valign:'middle',margin:2});
  });

  // Per-drone row mock
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.85,w:9.3,h:0.36,fill:{color:C.sky},line:{color:C.sky}});
  s.addText('PER-DRONE ROW', {x:0.35,y:1.85,w:9.3,h:0.36,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  const dItems = [
    ['Callsign','Tap to edit\nin real time'],
    ['Remote ID','Hardware\nidentifier'],
    ['Signal','4-bar strength\nindicator'],
    ['Earth icon','Sending to\nCaltopo'],
    ['Waypoints','Running total\nthis session'],
    ['BT4/BT5/WiFi','Message type\nbreakdown'],
  ];
  dItems.forEach((item,i) => {
    const bx = 0.35+i*(9.3/6);
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:2.21,w:9.3/6,h:0.72,fill:{color:C.white},line:{color:C.ltGray}});
    s.addText(item[0], {x:bx+0.05,y:2.21,w:9.3/6-0.1,h:0.34,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.navy,valign:'middle',margin:0});
    s.addText(item[1], {x:bx+0.05,y:2.53,w:9.3/6-0.1,h:0.38,fontSize:9.5,fontFace:'Calibri',color:C.dkGray,margin:0});
  });

  // Settings
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:3.06,w:9.3,h:0.36,fill:{color:C.dkGray},line:{color:C.dkGray}});
  s.addText('KEY SETTINGS (Menu → Settings)', {x:0.35,y:3.06,w:9.3,h:0.36,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  const settings = [
    ['Min Distance (ft)','Ignore waypoint if drone moved less than this — filters GPS noise'],
    ['Max Idle Time (min)','Stop tracking if no signal for X minutes — cleans up after landing'],
    ['New Track Delay (s)','Wait before creating a new live track for a newly detected drone'],
    ['Live Updates toggle','Enable/disable sending waypoints to Caltopo (useful for testing)'],
  ];
  settings.forEach((setting,i) => {
    const col=i%2, row=Math.floor(i/2);
    const bx=0.35+col*4.65, by=3.52+row*0.85;
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:4.55,h:0.75,fill:{color:C.white},line:{color:C.ltGray}});
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:0.08,h:0.75,fill:{color:C.orange},line:{color:C.orange}});
    s.addText(setting[0], {x:bx+0.15,y:by+0.06,w:4.3,h:0.25,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(setting[1], {x:bx+0.15,y:by+0.32,w:4.3,h:0.36,fontSize:10.5,fontFace:'Calibri',color:C.dkGray,margin:0});
  });

  s.addNotes("Caltopo RTT is your connectivity health indicator. Under 500ms is great. Over 2000ms indicates a slow connection. 0 means no connection to Caltopo.");
}

// ── SLIDE 8: MULTI-ZONE ──────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Covering Large Search Areas: Multi-Zone Operations');
  pg(s, 8);

  // Problem
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:0.85,w:4.45,h:1.55,fill:{color:C.red},line:{color:C.red},shadow:mk()});
  s.addText('The Challenge', {x:0.35,y:0.85,w:4.45,h:0.38,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addText([
    {text:"One Bridge can't cover a large or mountainous area",options:{bullet:true,breakLine:true}},
    {text:'Multiple independent zones create duplicate tracks on Caltopo',options:{bullet:true}},
  ], {x:0.45,y:1.28,w:4.25,h:1.0,fontSize:12,fontFace:'Calibri',color:C.white,valign:'top',paraSpaceAfter:6});

  s.addText('→', {x:4.88,y:1.38,w:0.5,h:0.42,fontSize:28,fontFace:'Trebuchet MS',bold:true,color:C.orange,align:'center',margin:0});

  // Solution
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:0.85,w:4.45,h:1.55,fill:{color:C.green},line:{color:C.green},shadow:mk()});
  s.addText('r2c-tracker Coordination', {x:5.2,y:0.85,w:4.45,h:0.38,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addText([
    {text:'Centralized hub assigns drone ownership to the zone that detects it first (and is closest)',options:{bullet:true,breakLine:true}},
    {text:'Only the owner writes to Caltopo — no duplicates',options:{bullet:true,breakLine:true}},
    {text:'Other zones relay sightings to the owner for extended coverage',options:{bullet:true}},
  ], {x:5.3,y:1.28,w:4.25,h:1.0,fontSize:11.5,fontFace:'Calibri',color:C.white,valign:'top',paraSpaceAfter:4});

  // Ownership flow header
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:2.55,w:9.3,h:0.36,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('HOW OWNERSHIP WORKS', {x:0.35,y:2.55,w:9.3,h:0.36,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});

  // 4 step cards — oval raised well above title to avoid overlap
  const ownerSteps = [
    ['1','First Detection','Zone that first detects a drone sends a "first_sighting" message to r2c-tracker'],
    ['2','Ownership Assigned','Tracker assigns ownership to the closest zone. Owner writes waypoints to Caltopo.'],
    ['3','Other Zones Relay','Non-owner zones forward sightings to the owner — extends coverage without duplicates'],
    ['4','Auto Failover','If owner loses signal for 45 sec, ownership transfers to next available zone automatically'],
  ];
  ownerSteps.forEach((step,i) => {
    const bx = 0.35+i*2.38;
    const cardY = 3.0;
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:cardY,w:2.28,h:2.48,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    // Number circle — anchored near top of card with generous gap before title
    s.addShape(pres.shapes.OVAL, {x:bx+0.89,y:cardY+0.08,w:0.50,h:0.50,fill:{color:C.orange},line:{color:C.orange}});
    s.addText(step[0], {x:bx+0.89,y:cardY+0.08,w:0.50,h:0.50,fontSize:14,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
    // Title positioned well below circle bottom (circle ends at cardY+0.58)
    s.addText(step[1], {x:bx+0.1,y:cardY+0.70,w:2.08,h:0.36,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.navy,align:'center',margin:0});
    s.addText(step[2], {x:bx+0.1,y:cardY+1.08,w:2.08,h:1.3,fontSize:10.5,fontFace:'Calibri',color:C.dkGray,align:'center',margin:0});
  });

  // MQTT fallback note
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:5.15,w:9.3,h:0.30,fill:{color:C.navyDk},line:{color:C.navyDk}});
  s.addText('Fallback: MQTT free-tier broker acts as backup arbiter when r2c-tracker is unreachable.', {
    x:0.45,y:5.15,w:9.1,h:0.30,fontSize:10,fontFace:'Calibri',italic:true,color:C.orangeLt,valign:'middle',margin:0
  });

  s.addNotes("Zone heartbeat fires every 15 seconds. Ownership expires after 45 seconds of silence. MQTT is a backup only — the free tier is not reliable enough to be primary.");
}

// ── SLIDE 9: ZONE PLACEMENT ──────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Placing Your Zones for Maximum Coverage');
  pg(s, 9);

  const items = [
    ['▲','Follow your Caltopo segments','Natural segment boundaries from your assignment map usually define good zone boundaries'],
    ['◉','Prioritize elevation','Choose sites with clear line-of-sight to flight areas — ridge tops, peaks, or rooftops'],
    ['↑','Elevate the Bridge','DS110 on a 15+ ft tripod reaches thousands of feet — enough to cover a full segment'],
    ['☁','Plan for connectivity','Cellular LTE is sufficient. In dead zones, a battery-powered Starlink Mini works well'],
    ['⊕','Mark zones on Caltopo','Each R2C instance places an "R2C Coordinator" marker showing its location and status'],
    ['⟳','Plan 20-30% coverage overlap','Overlap between zones ensures seamless track handoffs as drones cross boundaries'],
  ];

  items.forEach((item,i) => {
    const col=i%2, row=Math.floor(i/2);
    const bx=0.35+col*4.9, by=0.88+row*1.45;
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:4.6,h:1.28,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:0.08,h:1.28,fill:{color:C.orange},line:{color:C.orange}});
    s.addText(item[0], {x:bx+0.15,y:by+0.08,w:0.48,h:0.48,fontSize:18,color:C.navy,align:'center',margin:0});
    s.addText(item[1], {x:bx+0.65,y:by+0.10,w:3.85,h:0.30,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(item[2], {x:bx+0.65,y:by+0.40,w:3.85,h:0.75,fontSize:11,fontFace:'Calibri',color:C.dkGray,margin:0});
  });

  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:5.12,w:9.3,h:0.32,fill:{color:C.navyDk},line:{color:C.navyDk}});
  s.addText('Sierra Nevada tip: Teams have used battery-powered Starlink Minis on ridge zones with no cellular signal — one device + Bridge covers an entire search segment solo.', {
    x:0.45,y:5.12,w:9.1,h:0.32,fontSize:10,fontFace:'Calibri',italic:true,color:C.orangeLt,valign:'middle',margin:0
  });

  s.addNotes("In the Sierra Nevada, volunteers have run Starlink Minis powered by goal-zero type battery packs on ridge zones. One device running RID2Caltopo + the Bridge covers an entire search segment solo.");
}

// ── SLIDE 10: FIELD DIAGRAM ──────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: '182838' }; // dark mountain sky

  // Terrain strip at bottom
  s.addShape(pres.shapes.RECTANGLE, {x:0,y:4.45,w:10,h:1.175,fill:{color:C.terrain},line:{color:C.terrain}});
  // Mountain silhouette shapes (overlapping rectangles skewed via text terrain label)
  const mtns = [{x:0.0,w:2.2},{x:1.5,w:2.8},{x:3.8,w:2.0},{x:5.2,w:3.0},{x:7.5,w:2.5}];
  const mtnH = [1.0,1.4,0.8,1.2,0.9];
  mtns.forEach((m,i) => {
    // Simple mountain block
    s.addShape(pres.shapes.RECTANGLE, {
      x:m.x, y:4.45-mtnH[i]+0.2, w:m.w, h:mtnH[i],
      fill:{color:'1E1005'}, line:{color:'1E1005'}
    });
  });

  // Title bar
  hdr(s, 'Multi-Zone Field Operation: Mountainous Search Area');
  pg(s, 10);

  // ── r2c-tracker service (top center) ──
  const trackerX=3.55, trackerY=0.82, trackerW=2.9, trackerH=0.72;
  s.addShape(pres.shapes.RECTANGLE, {x:trackerX,y:trackerY,w:trackerW,h:trackerH,fill:{color:C.orange},line:{color:C.orange},shadow:mk()});
  s.addText('r2c-tracker + Internet', {x:trackerX,y:trackerY,w:trackerW,h:0.36,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.navyDk,align:'center',valign:'middle',margin:0});
  s.addText('Coordination Service', {x:trackerX,y:trackerY+0.36,w:trackerW,h:0.30,fontSize:9,fontFace:'Calibri',color:C.navyDk,align:'center',valign:'middle',margin:0});

  // ── Connectivity lines (thin, dashed feel via light color) ──
  // From tracker bottom-center (5.0, 1.54) to each zone/IC
  const hubX=5.0, hubY=trackerY+trackerH; // (5.0, 1.54)

  // Helper: draw a line from (x1,y1) to (x2,y2)
  function drawLine(x1,y1,x2,y2,color) {
    const dx=x2-x1, dy=y2-y1;
    const lx=Math.min(x1,x2), ly=Math.min(y1,y2);
    const lw=Math.abs(dx), lh=Math.abs(dy);
    const needFlipV = (dx>=0 && dy<0) || (dx<0 && dy>=0);
    s.addShape(pres.shapes.LINE, {x:lx,y:ly,w:lw||0.02,h:lh||0.02,
      flipV:needFlipV,
      line:{color:color||'4A7AA8',width:1.5,dashType:'dash'}});
  }

  // Zone centers (approx)
  const zones = [
    {label:'Zone A',sub:'North Ridge',cx:1.25,cy:2.55},
    {label:'Zone B',sub:'East Canyon',cx:5.0,cy:2.0},
    {label:'Zone C',sub:'West Valley',cx:8.75,cy:2.55},
  ];
  const icCX=5.0, icCY=4.0;

  zones.forEach(z => drawLine(hubX, hubY, z.cx, z.cy, '4A8AAA'));
  drawLine(hubX, hubY, icCX, icCY, '4A8AAA');

  // ── Zone A (left) ──
  const zW=2.0, zH=1.55;
  zones.forEach((z,i) => {
    const zx=z.cx-zW/2, zy=z.cy-zH/2;
    s.addShape(pres.shapes.RECTANGLE, {x:zx,y:zy,w:zW,h:zH,fill:{color:C.navy},line:{color:'3A6AAA'},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:zx,y:zy,w:zW,h:0.30,fill:{color:'2E5FAA'},line:{color:'2E5FAA'}});
    s.addText(`${z.label} — ${z.sub}`, {x:zx,y:zy,w:zW,h:0.30,fontSize:9.5,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
    s.addText([
      {text:'✈  Drone + Pilot',options:{breakLine:true}},
      {text:'📡  RID2Caltopo + DS Bridge',options:{breakLine:true}},
      {text:'📶  Starlink Mini or LTE hotspot',options:{}},
    ], {x:zx+0.1,y:zy+0.36,w:zW-0.2,h:1.08,fontSize:9.5,fontFace:'Calibri',color:C.white,valign:'top',margin:0,paraSpaceAfter:4});
  });

  // ── IC Command Post (bottom center) ──
  const icW=2.6, icH=1.1;
  const icX=icCX-icW/2, icY=icCY-icH/2;
  s.addShape(pres.shapes.RECTANGLE, {x:icX,y:icY,w:icW,h:icH,fill:{color:'1A4020'},line:{color:'2A6030'},shadow:mk()});
  s.addShape(pres.shapes.RECTANGLE, {x:icX,y:icY,w:icW,h:0.30,fill:{color:'2A6030'},line:{color:'2A6030'}});
  s.addText('IC Command Post', {x:icX,y:icY,w:icW,h:0.30,fontSize:10,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addText([
    {text:'🚐  Vehicle with Starlink Mini',options:{breakLine:true}},
    {text:'🗺  Caltopo — live drone tracks visible',options:{}},
  ], {x:icX+0.1,y:icY+0.35,w:icW-0.2,h:0.65,fontSize:9.5,fontFace:'Calibri',color:C.white,valign:'top',margin:0,paraSpaceAfter:4});

  // Legend
  s.addShape(pres.shapes.RECTANGLE, {x:0.2,y:4.5,w:3.2,h:0.85,fill:{color:'0D1E2C'},line:{color:'2A4A6A'}});
  s.addText('Dashed lines = internet connections\nAll zones coordinate via r2c-tracker\nIC views live tracks on Caltopo', {
    x:0.3,y:4.52,w:3.0,h:0.80,fontSize:8.5,fontFace:'Calibri',color:C.mdGray,valign:'top',margin:0,paraSpaceAfter:2
  });

  s.addNotes("This diagram shows a typical 3-zone configuration. Each zone has its own Starlink Mini or LTE hotspot. The r2c-tracker service (cloud-hosted) assigns drone ownership so each drone track appears exactly once on the IC's Caltopo map.");
}

// ── SLIDE 11: POST-INCIDENT ──────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'After the Operation: Flight Analytics');
  pg(s, 11);

  const procSteps = [
    {n:'1',title:'Export from Caltopo',    desc:'Export the drone track folder as GeoJSON files from your Caltopo map'},
    {n:'2',title:'Upload to r2c-tracker',  desc:'POST to /upload with your API key (X-SAR-Token header), or use the admin UI'},
    {n:'3',title:'Automatic analysis',     desc:'Tracker parses metadata, calculates metrics, and pulls historical weather'},
  ];

  procSteps.forEach((ps,i) => {
    const bx = 0.35+i*3.18;
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:0.88,w:2.98,h:1.9,fill:{color:C.navy},line:{color:C.navy},shadow:mk()});
    s.addShape(pres.shapes.OVAL,      {x:bx+1.24,y:0.93,w:0.50,h:0.50,fill:{color:C.orange},line:{color:C.orange}});
    s.addText(ps.n, {x:bx+1.24,y:0.93,w:0.50,h:0.50,fontSize:16,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
    s.addText(ps.title, {x:bx+0.1,y:1.52,w:2.78,h:0.35,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.orangeLt,align:'center',margin:0});
    s.addText(ps.desc,  {x:bx+0.1,y:1.88,w:2.78,h:0.82,fontSize:10.5,fontFace:'Calibri',color:C.white,align:'center',valign:'top',margin:0});
  });
  s.addText('→', {x:3.40,y:1.25,w:0.30,h:0.45,fontSize:22,bold:true,color:C.orange,align:'center',margin:0});
  s.addText('→', {x:6.58,y:1.25,w:0.30,h:0.45,fontSize:22,bold:true,color:C.orange,align:'center',margin:0});

  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:2.95,w:9.3,h:0.36,fill:{color:C.dkGray},line:{color:C.dkGray}});
  s.addText('WHAT YOU GET', {x:0.35,y:2.95,w:9.3,h:0.36,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});

  const outcomes = [
    ['Flight Hours & Distance','Per-pilot and per-aircraft totals from every upload'],
    ['Team Leaderboard','Top pilots, aircraft, and flight hours across all incidents'],
    ['Weather Context','Temperature, wind, gusts, cloud cover for each flight time and location'],
    ['Day/Night Breakdown','Sunrise/sunset-aware day vs. night flight time calculation'],
    ['Overlap Detection','Flags duplicate or redundant tracks from multi-zone ops'],
    ['CSV Export','Full flight log export for after-action reports and compliance'],
  ];
  outcomes.forEach((o,i) => {
    const col=i%3, row=Math.floor(i/3);
    const bx=0.35+col*3.18, by=3.42+row*0.98;
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:3.08,h:0.88,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:0.07,h:0.88,fill:{color:C.sky},line:{color:C.sky}});
    s.addText(o[0], {x:bx+0.15,y:by+0.08,w:2.85,h:0.28,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(o[1], {x:bx+0.15,y:by+0.37,w:2.85,h:0.44,fontSize:10.5,fontFace:'Calibri',color:C.dkGray,margin:0});
  });

  s.addNotes("Export the 'Live Drone Tracks' folder from Caltopo as GeoJSON. The tracker parses it and creates individual flight records per drone per flight segment, matched with historical weather from Open-Meteo.");
}

// ── SLIDE 12: MUTUAL AID SCENARIOS ───────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Mutual Aid: Joining Another Organization\'s Operation');
  pg(s, 12);

  s.addText('Two common scenarios when your team responds to a Mutual Aid request:', {
    x:0.35,y:0.82,w:9.3,h:0.35,fontSize:13,fontFace:'Calibri',italic:true,color:C.dkGray,margin:0
  });

  // Scenario A
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.25,w:4.45,h:0.52,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('Scenario A — Visiting team with Caltopo Teams', {x:0.35,y:1.25,w:4.45,h:0.52,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:1.77,w:4.45,h:3.6,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});

  s.addText('Your team has a Caltopo Teams account.\nThe hosting org also has Teams.', {
    x:0.45,y:1.85,w:4.25,h:0.52,fontSize:12,fontFace:'Calibri',italic:true,color:C.dkGray,margin:0
  });
  s.addText([
    {text:'Host shares a Mutual Aid config package with you via QR code',options:{bullet:true,breakLine:true}},
    {text:"MA config contains the host's map_id and a credential scoped to write their map only",options:{bullet:true,breakLine:true}},
    {text:'Your RID2Caltopo app loads the MA config and begins writing drone tracks directly into the host\'s Caltopo map',options:{bullet:true,breakLine:true}},
    {text:"Host's IC sees your drones labeled with your callsigns on their existing map",options:{bullet:true,breakLine:true}},
    {text:'Your own org map is unaffected — MA config is separate from your home credentials',options:{bullet:true}},
  ], {x:0.45,y:2.45,w:4.25,h:2.8,fontSize:11.5,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:7});

  // Scenario B
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:1.25,w:4.45,h:0.52,fill:{color:C.sky},line:{color:C.sky}});
  s.addText('Scenario B — Visiting team, host has no Teams', {x:5.2,y:1.25,w:4.45,h:0.52,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:5.2,y:1.77,w:4.45,h:3.6,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});

  s.addText('Your team has Caltopo Teams.\nThe hosting org uses free Caltopo only.', {
    x:5.3,y:1.85,w:4.25,h:0.52,fontSize:12,fontFace:'Calibri',italic:true,color:C.dkGray,margin:0
  });
  s.addText([
    {text:"Your team adds a bookmark to the host team's map into your Teams account that RID2Caltopo can write into",options:{bullet:true,breakLine:true}},
    {text:"Host's IC sees your drone tracks in their map",options:{bullet:true}},
  ], {x:5.3,y:2.45,w:4.25,h:2.8,fontSize:11.5,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:7});

  s.addNotes("Scenario A is the preferred setup when both orgs have Teams. The MA config QR code is generated in the app by the host's coordinator. Scenario B works with any free Caltopo account — the bookmarked map is read-only for the guest.");
}

// ── SLIDE 13: NOTAM ──────────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Airspace Awareness: NOTAM Integration');
  pg(s, 13);

  s.addText('Stay ahead of TFRs and airspace restrictions', {
    x:0.35,y:0.90,w:5.5,h:0.38,fontSize:15,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0
  });
  s.addText([
    {text:'Display active NOTAMs near your search area',options:{bullet:true,breakLine:true}},
    {text:'Enable via Settings → NOTAM Integration',options:{bullet:true,breakLine:true}},
    {text:'Configurable detection radius: 2, 4, 8, or 16 nautical miles',options:{bullet:true,breakLine:true}},
    {text:'Auto-refreshes on a configurable interval (30-minute minimum)',options:{bullet:true,breakLine:true}},
    {text:'Covers airspace restrictions, TFRs, and Class B/C airspace alerts',options:{bullet:true}},
  ], {x:0.35,y:1.35,w:5.5,h:2.55,fontSize:13,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:8});

  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:4.05,w:5.5,h:1.35,fill:{color:C.navyDk},line:{color:C.navyDk},shadow:mk()});
  s.addShape(pres.shapes.RECTANGLE, {x:0.35,y:4.05,w:0.08,h:1.35,fill:{color:C.yellow},line:{color:C.yellow}});
  s.addText('IMPORTANT', {x:0.52,y:4.10,w:5.2,h:0.28,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.yellow,margin:0});
  s.addText('NOTAM alerts in-app are a supplement, not a replacement for your pre-flight NOTAM check via 1800wxbrief.com or your ATC provider. TFRs can be issued mid-operation on short notice.', {
    x:0.52,y:4.40,w:5.15,h:0.88,fontSize:11.5,fontFace:'Calibri',color:C.white,margin:0
  });

  // Right: use cases — no small dot decorators
  s.addShape(pres.shapes.RECTANGLE, {x:6.1,y:0.85,w:3.55,h:4.55,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
  s.addShape(pres.shapes.RECTANGLE, {x:6.1,y:0.85,w:3.55,h:0.42,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('When This Matters Most', {x:6.1,y:0.85,w:3.55,h:0.42,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});

  const cases = [
    ['Wildfire operations','TFRs issued within minutes — NOTAM alerts catch changes after your morning brief'],
    ['Near airports','Operations within 5nm may require COA — NOTAM check confirms current status'],
    ['Mountain rescues','Temporary restricted areas around long-duration incidents'],
    ['Multi-day incidents','Airspace picture changes overnight — auto-refresh keeps the app current'],
  ];
  let cy=1.38;
  cases.forEach(c => {
    s.addText(c[0], {x:6.2,y:cy,w:3.3,h:0.26,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.navy,margin:0});
    s.addText(c[1], {x:6.2,y:cy+0.25,w:3.3,h:0.52,fontSize:10.5,fontFace:'Calibri',color:C.dkGray,margin:0});
    // Thin divider between cases
    if (cy < 3.5) s.addShape(pres.shapes.RECTANGLE, {x:6.2,y:cy+0.82,w:3.2,h:0.02,fill:{color:C.ltGray},line:{color:C.ltGray}});
    cy += 0.93;
  });

  s.addNotes("During wildfire operations, TFRs can appear with very short notice. Minimum auto-refresh is 30 minutes.");
}

// ── SLIDE 14: TROUBLESHOOTING ────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Common Issues & Quick Fixes');
  pg(s, 14);

  const issues = [
    {
      issue:'No drones appearing in the app',
      fixes:['Check Bridge is powered and connected via USB','Verify Bluetooth AND WiFi are enabled on the device','Remote ID only broadcasts in flight — confirm drones are airborne','Move Bridge to higher elevation or closer to the flight area'],
    },{
      issue:'Caltopo RTT shows 0 or red',
      fixes:['Check internet connectivity on the device','Verify credentials.json has the correct API keys','Confirm map_id matches the active Caltopo map URL','Toggle "Live Updates" off and back on in Settings'],
    },{
      issue:'Duplicate tracks on Caltopo',
      fixes:['Verify all zones have the same tracker_url_prefix in credentials.json','Check that r2c-tracker service is running and reachable','Look for "R2C Coordinator" markers on the map — zones should show connected','Restart all zone apps if tracker was recently rebooted'],
    },{
      issue:'Wrong callsign on a track',
      fixes:['Tap the callsign in the app to edit it — changes sync to Caltopo immediately','Update ridmap.json for future operations and re-share via QR code','If still wrong, check that ridmap.json remote ID entry matches the actual drone'],
    },
  ];

  issues.forEach((issue,i) => {
    const col=i%2, row=Math.floor(i/2);
    const bx=0.35+col*4.78, by=0.88+row*2.32;
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:4.58,h:2.12,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:4.58,h:0.42,fill:{color:C.navyDk},line:{color:C.navyDk}});
    s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:0.08,h:0.42,fill:{color:C.red},line:{color:C.red}});
    s.addText(issue.issue, {x:bx+0.15,y:by,w:4.35,h:0.42,fontSize:12,fontFace:'Trebuchet MS',bold:true,color:C.white,valign:'middle',margin:0});
    const fixItems = issue.fixes.map((f,fi) => ({text:f,options:{bullet:true,breakLine:fi<issue.fixes.length-1}}));
    s.addText(fixItems, {x:bx+0.10,y:by+0.48,w:4.38,h:1.56,fontSize:10.5,fontFace:'Calibri',color:C.black,valign:'top',paraSpaceAfter:4});
  });

  s.addNotes("Most common field issue: forgetting to enable both Bluetooth AND WiFi. Remote ID uses both protocols — Skydio drones use WiFi NaN which requires WiFi scanning to be enabled.");
}

// ── SLIDE 15: CHECKLIST ──────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  hdr(s, 'Pre-Flight Checklist');
  pg(s, 15);

  const items = [
    {text:'ridmap.json updated with all drones flying today',              pc:C.sky    },
    {text:'credentials.json loaded with correct map ID for this incident', pc:C.sky    },
    {text:'Dronescout Bridge mounted at 15+ ft and powered',              pc:C.orange },
    {text:'RID2Caltopo launched and showing correct map ID in header',     pc:C.navy   },
    {text:'Caltopo round-trip time (RTT) is low (green)',                 pc:C.navy   },
    {text:'Test drone visible in app during pre-flight check',            pc:C.green  },
    {text:'"Live Drone Tracks" folder visible on Caltopo map',            pc:C.green  },
    {text:'IC / Air Boss confirmed they can see the tracks on Caltopo',   pc:C.green  },
    {text:'(Multi-zone) All zones show as connected in r2c-tracker',      pc:C.dkGray },
    {text:'NOTAM check complete for operation area',                      pc:C.yellow },
  ];

  const col1=items.slice(0,5), col2=items.slice(5);
  [col1,col2].forEach((col,ci) => {
    const bx=0.35+ci*4.83;
    col.forEach((item,i) => {
      const by=0.88+i*0.91;
      s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:4.63,h:0.79,fill:{color:C.white},line:{color:C.ltGray},shadow:mk()});
      s.addShape(pres.shapes.RECTANGLE, {x:bx,y:by,w:0.08,h:0.79,fill:{color:item.pc},line:{color:item.pc}});
      // Checkbox outline only (no fill dot)
      s.addShape(pres.shapes.RECTANGLE, {x:bx+0.18,y:by+0.23,w:0.30,h:0.30,fill:{color:C.white},line:{color:item.pc,width:2}});
      s.addText(item.text, {x:bx+0.57,y:by+0.10,w:3.97,h:0.57,fontSize:11.5,fontFace:'Calibri',color:C.black,valign:'middle',margin:0});
    });
  });

  s.addNotes("Print and laminate for field use. Most commonly missed: getting IC confirmation they can see Caltopo tracks before the first drone launches.");
}

// ── SLIDE 16: SUMMARY ────────────────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: C.navyDk };
  s.addShape(pres.shapes.RECTANGLE, {x:0,y:0,w:10,h:0.22,fill:{color:C.orange},line:{color:C.orange}});
  s.addShape(pres.shapes.RECTANGLE, {x:0,y:5.405,w:10,h:0.22,fill:{color:C.orange},line:{color:C.orange}});
  s.addText('Key Takeaways', {x:0.5,y:0.28,w:9,h:0.48,fontSize:26,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',margin:0});

  const takeaways = [
    'RID2Caltopo converts FAA Remote ID broadcasts into live Caltopo tracks — no drone modifications needed',
    'Works with any modern FAA-compliant drone (DJI, Skydio, Autel, and others)',
    'Single-zone setup takes under 10 minutes once config files are ready',
    'Multi-zone coordination via r2c-tracker eliminates duplicate tracks automatically',
    'Post-incident analytics provide weather-contextualized flight records for after-action reports',
  ];
  takeaways.forEach((t,i) => {
    const by=0.92+i*0.68;
    s.addShape(pres.shapes.RECTANGLE, {x:0.5,y:by,w:7.2,h:0.58,fill:{color:C.navy},line:{color:C.navy}});
    s.addShape(pres.shapes.RECTANGLE, {x:0.5,y:by,w:0.08,h:0.58,fill:{color:C.orange},line:{color:C.orange}});
    // Numbered label — wide enough so text is clearly visible
    s.addShape(pres.shapes.OVAL, {x:0.60,y:by+0.09,w:0.40,h:0.40,fill:{color:C.orange},line:{color:C.orange}});
    s.addText(String(i+1), {x:0.60,y:by+0.09,w:0.40,h:0.40,fontSize:13,fontFace:'Trebuchet MS',bold:true,color:C.white,align:'center',valign:'middle',margin:0});
    s.addText(t, {x:1.10,y:by+0.05,w:6.5,h:0.48,fontSize:12.5,fontFace:'Calibri',color:C.white,valign:'middle',margin:0});
  });

  // Resources panel
  s.addShape(pres.shapes.RECTANGLE, {x:7.82,y:0.88,w:1.83,h:4.32,fill:{color:C.navy},line:{color:C.navy}});
  s.addText('Resources', {x:7.82,y:0.90,w:1.83,h:0.34,fontSize:11,fontFace:'Trebuchet MS',bold:true,color:C.orangeLt,align:'center',margin:0});
  s.addShape(pres.shapes.RECTANGLE, {x:7.87,y:1.24,w:1.73,h:0.03,fill:{color:C.orange},line:{color:C.orange}});

  const resources = [
    'RID2Caltopo\nGitHub repo',
    'r2c-tracker\nGitHub repo',
    'Caltopo Teams\nAPI docs',
    'FAA Remote ID\ninfo page',
    'Questions:\nkjtsar@kjt.us',
  ];
  resources.forEach((r,i) => {
    s.addText(r, {x:7.87,y:1.32+i*0.62,w:1.73,h:0.52,fontSize:10,fontFace:'Calibri',color:C.white,align:'center',valign:'middle',margin:2});
    if (i<resources.length-1) s.addShape(pres.shapes.RECTANGLE, {x:7.92,y:1.84+i*0.62,w:1.63,h:0.02,fill:{color:C.dkGray},line:{color:C.dkGray}});
  });

  s.addNotes("For questions, contact kjtsar@kjt.us or file a GitHub issue on the RID2Caltopo repository.");
}

// ── WRITE FILE ───────────────────────────────────────────────────────────────
pres.writeFile({ fileName: '/Users/kjt/Projects/RID2Caltopo/docs/SAR_Drone_Tracking_Guide.pptx' })
  .then(() => console.log('DONE'))
  .catch(err => { console.error('ERROR:', err); process.exit(1); });
