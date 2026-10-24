'use strict';
const crypto = require('crypto');
const jwt = require('jsonwebtoken');

const allowedAmounts = new Set([10000,20000,50000,100000,200000,500000,1000000]);
const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const usernameRe = /^[A-Za-z0-9_]{3,16}$/;

function equal(a,b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const x=Buffer.from(a), y=Buffer.from(b);
  return x.length===y.length && crypto.timingSafeEqual(x,y);
}
function pluginOnly(req,res,next) {
  if (!process.env.PLUGIN_SHARED_SECRET ||
      !equal(req.get('X-Plugin-Key') || '', process.env.PLUGIN_SHARED_SECRET))
    return res.status(401).json({success:false,error:'unauthorized'});
  next();
}
function webOnly(req,res,next) {
  const m=/^Bearer\s+(.+)$/i.exec(req.get('Authorization')||'');
  if (!m || !process.env.JWT_SECRET) return res.status(401).json({success:false,error:'login_required'});
  try {
    const c=jwt.verify(m[1],process.env.JWT_SECRET,{algorithms:['HS256'],issuer:'nightmare-tcyt',audience:'nightmare-tcyt-web'});
    if (!uuidRe.test(c.sub||'')) throw new Error('bad sub');
    req.mcUser={uuid:c.sub,username:c.username}; next();
  } catch { return res.status(401).json({success:false,error:'invalid_session'}); }
}
function validSepay(req) {
  const secret=process.env.SEPAY_WEBHOOK_SECRET;
  const ts=req.get('X-SePay-Timestamp')||'', sig=req.get('X-SePay-Signature')||'';
  if (!secret || !req.rawBody || !/^\d{10}$/.test(ts)) return false;
  if (Math.abs(Math.floor(Date.now()/1000)-Number(ts))>300) return false;
  const expected='sha256='+crypto.createHmac('sha256',secret).update(ts+'.').update(req.rawBody).digest('hex');
  return equal(sig.toLowerCase(),expected.toLowerCase());
}
function findCode(p) {
  const s=(`${p.code||''} ${p.content||''}`).toUpperCase();
  return (s.match(/\bNTCYT[0-9A-F]{12}\b/)||[])[0]||null;
}

function registerNightmarePayments(app,pool) {
  // Called after express.json middleware that preserves req.rawBody as described in README.
  app.post('/internal/link/create',pluginOnly,async(req,res)=>{
    const uuid=String(req.body?.uuid||''), username=String(req.body?.username||'');
    if(!uuidRe.test(uuid)||!usernameRe.test(username)) return res.status(400).json({success:false,error:'invalid_player'});
    const code=crypto.randomBytes(6).toString('hex').toUpperCase();
    const hash=crypto.createHash('sha256').update(code).digest('hex');
    try {
      await pool.query(`UPDATE nightmare_link_codes SET expires_at=NOW()
        WHERE minecraft_uuid=$1 AND used_at IS NULL AND expires_at>NOW()`,[uuid]);
      await pool.query(`INSERT INTO nightmare_link_codes(code_hash,minecraft_uuid,minecraft_username,expires_at)
        VALUES($1,$2,$3,NOW()+INTERVAL '5 minutes')`,[hash,uuid,username]);
      res.json({success:true,code,expiresInSeconds:300});
    } catch(e) { console.error('link-create:',e.message); res.status(500).json({success:false,error:'server_error'}); }
  });

  app.post('/api/minecraft/link/claim',async(req,res)=>{
    const code=String(req.body?.code||'').trim().toUpperCase();
    if(!/^[0-9A-F]{12}$/.test(code)) return res.status(400).json({success:false,error:'invalid_code'});
    if(!process.env.JWT_SECRET) return res.status(503).json({success:false,error:'auth_not_configured'});
    const hash=crypto.createHash('sha256').update(code).digest('hex'), c=await pool.connect();
    let p;
    try {
      await c.query('BEGIN');
      const r=await c.query(`SELECT minecraft_uuid,minecraft_username FROM nightmare_link_codes
        WHERE code_hash=$1 AND used_at IS NULL AND expires_at>NOW() FOR UPDATE`,[hash]);
      if(!r.rowCount){await c.query('ROLLBACK');return res.status(400).json({success:false,error:'code_invalid_or_expired'});}
      p=r.rows[0];
      await c.query('UPDATE nightmare_link_codes SET used_at=NOW() WHERE code_hash=$1 AND used_at IS NULL',[hash]);
      await c.query('COMMIT');
    } catch(e){await c.query('ROLLBACK');console.error('link-claim:',e.message);return res.status(500).json({success:false,error:'server_error'});}
    finally{c.release();}
    const token=jwt.sign({username:p.minecraft_username},process.env.JWT_SECRET,{
      subject:p.minecraft_uuid,issuer:'nightmare-tcyt',audience:'nightmare-tcyt-web',algorithm:'HS256',expiresIn:'12h'
    });
    res.json({success:true,token,player:{uuid:p.minecraft_uuid,username:p.minecraft_username},expiresInSeconds:43200});
  });

  app.post('/api/orders',webOnly,async(req,res)=>{
    const amount=Number(req.body?.amountVnd), rate=Number.parseInt(process.env.POINTS_PER_1000_VND||'',10);
    if(!allowedAmounts.has(amount)) return res.status(400).json({success:false,error:'unsupported_amount'});
    if(!Number.isSafeInteger(rate)||rate<1||rate>100000) return res.status(503).json({success:false,error:'points_rate_not_configured'});
    const points=Math.floor(amount/1000*rate), id=crypto.randomUUID(), code='NTCYT'+crypto.randomBytes(6).toString('hex').toUpperCase();
    try {
      await pool.query(`INSERT INTO nightmare_orders(id,payment_code,minecraft_uuid,minecraft_username,amount_vnd,points)
        VALUES($1,$2,$3,$4,$5,$6)`,[id,code,req.mcUser.uuid,req.mcUser.username,amount,points]);
      res.status(201).json({success:true,order:{id,paymentCode:code,amountVnd:amount,points,transferContent:code,
        bank:{name:process.env.BANK_NAME||null,owner:process.env.BANK_OWNER||null,account:process.env.BANK_ACCOUNT||null},status:'pending'}});
    } catch(e){console.error('order-create:',e.message);res.status(500).json({success:false,error:'server_error'});}
  });

  app.get('/api/orders/:id',webOnly,async(req,res)=>{
    try {
      const r=await pool.query(`SELECT id,payment_code,amount_vnd,points,status,created_at,paid_at,fulfilled_at
        FROM nightmare_orders WHERE id=$1 AND minecraft_uuid=$2`,[req.params.id,req.mcUser.uuid]);
      if(!r.rowCount)return res.status(404).json({success:false,error:'order_not_found'});
      const o=r.rows[0];
      res.json({success:true,order:{id:o.id,paymentCode:o.payment_code,amountVnd:Number(o.amount_vnd),points:o.points,status:o.status,createdAt:o.created_at,paidAt:o.paid_at,fulfilledAt:o.fulfilled_at}});
    } catch(e){console.error('order-status:',e.message);res.status(500).json({success:false,error:'server_error'});}
  });

  app.post('/webhooks/sepay',async(req,res)=>{
    if(!validSepay(req))return res.status(401).json({success:false,error:'invalid_signature_or_timestamp'});
    let p; try{p=JSON.parse(req.rawBody.toString('utf8'));}catch{return res.status(400).json({success:false,error:'invalid_json'});}
    const tx=String(p.id??''), amount=Number(p.transferAmount), incoming=String(p.transferType||'').toLowerCase()==='in';
    const acct=String(p.accountNumber||''), expectedAcct=String(process.env.SEPAY_ACCOUNT_NUMBER||''), code=findCode(p);
    if(!tx||tx.length>100||!Number.isSafeInteger(amount)||amount<=0)return res.status(400).json({success:false,error:'invalid_transaction'});
    const c=await pool.connect();
    try {
      await c.query('BEGIN');
      const ins=await c.query(`INSERT INTO nightmare_payment_events(transaction_id,status,amount_vnd,account_number,payload)
        VALUES($1,'received',$2,$3,$4::jsonb) ON CONFLICT(transaction_id) DO NOTHING RETURNING transaction_id`,
        [tx,amount,acct||null,JSON.stringify(p)]);
      if(!ins.rowCount){await c.query('COMMIT');return res.status(200).json({success:true,duplicate:true});}
      if(!incoming||!expectedAcct||acct!==expectedAcct||!code){
        await c.query(`UPDATE nightmare_payment_events SET status='ignored' WHERE transaction_id=$1`,[tx]);
        await c.query('COMMIT');return res.status(200).json({success:true,matched:false});
      }
      const r=await c.query('SELECT id,amount_vnd,status FROM nightmare_orders WHERE payment_code=$1 FOR UPDATE',[code]);
      if(!r.rowCount){
        await c.query(`UPDATE nightmare_payment_events SET status='unmatched' WHERE transaction_id=$1`,[tx]);
        await c.query('COMMIT');return res.status(200).json({success:true,matched:false});
      }
      const o=r.rows[0];
      if(Number(o.amount_vnd)!==amount){
        await c.query(`UPDATE nightmare_payment_events SET status='amount_mismatch',order_id=$2 WHERE transaction_id=$1`,[tx,o.id]);
        await c.query('COMMIT');return res.status(200).json({success:true,matched:false,reason:'amount_mismatch'});
      }
      if(o.status!=='pending'){
        await c.query(`UPDATE nightmare_payment_events SET status='order_not_pending',order_id=$2 WHERE transaction_id=$1`,[tx,o.id]);
        await c.query('COMMIT');return res.status(200).json({success:true,matched:false});
      }
      const paid=await c.query(`UPDATE nightmare_orders SET status='paid',paid_at=NOW()
        WHERE id=$1 AND status='pending' AND amount_vnd=$2 RETURNING id,minecraft_uuid,points`,[o.id,amount]);
      if(paid.rowCount){
        const x=paid.rows[0];
        await c.query(`INSERT INTO nightmare_point_queue(order_id,minecraft_uuid,points)
          VALUES($1,$2,$3) ON CONFLICT(order_id) DO NOTHING`,[x.id,x.minecraft_uuid,x.points]);
        await c.query(`UPDATE nightmare_payment_events SET status='paid',order_id=$2 WHERE transaction_id=$1`,[tx,x.id]);
      }
      await c.query('COMMIT');return res.status(200).json({success:true,matched:Boolean(paid.rowCount)});
    } catch(e){await c.query('ROLLBACK');console.error('sepay-webhook:',e.message);return res.status(500).json({success:false,error:'processing_failed'});}
    finally{c.release();}
  });

  app.post('/internal/points/claim',pluginOnly,async(req,res)=>{
    const c=await pool.connect();
    try {
      await c.query('BEGIN');
      const r=await c.query(`SELECT order_id,minecraft_uuid,points FROM nightmare_point_queue
        WHERE status='pending' OR (status='processing' AND locked_until<NOW())
        ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1`);
      if(!r.rowCount){await c.query('COMMIT');return res.json({success:true,job:null});}
      const j=r.rows[0];
      await c.query(`UPDATE nightmare_point_queue SET status='processing',attempts=attempts+1,
        locked_until=NOW()+INTERVAL '2 minutes',updated_at=NOW() WHERE order_id=$1`,[j.order_id]);
      await c.query('COMMIT');res.json({success:true,job:{id:j.order_id,uuid:j.minecraft_uuid,points:j.points}});
    } catch(e){await c.query('ROLLBACK');console.error('queue-claim:',e.message);res.status(500).json({success:false,error:'server_error'});}
    finally{c.release();}
  });

  app.post('/internal/points/:id/ack',pluginOnly,async(req,res)=>{
    const status=req.body?.status;
    if(!['done','review'].includes(status))return res.status(400).json({success:false,error:'invalid_status'});
    const c=await pool.connect();
    try {
      await c.query('BEGIN');
      const q=await c.query('SELECT status FROM nightmare_point_queue WHERE order_id=$1 FOR UPDATE',[req.params.id]);
      if(!q.rowCount){await c.query('ROLLBACK');return res.status(404).json({success:false,error:'job_not_found'});}
      if(q.rows[0].status==='done'){await c.query('COMMIT');return res.json({success:true,status:'done'});}
      if(q.rows[0].status==='review'&&status==='done'){await c.query('COMMIT');return res.status(409).json({success:false,error:'manual_review_required'});}
      await c.query(`UPDATE nightmare_point_queue SET status=$2,locked_until=NULL,updated_at=NOW(),
        last_error=CASE WHEN $2='review' THEN 'Manual reconciliation required' ELSE NULL END WHERE order_id=$1`,
        [req.params.id,status]);
      await c.query(`UPDATE nightmare_orders SET status=CASE WHEN $2='done' THEN 'fulfilled' ELSE 'needs_review' END,
        fulfilled_at=CASE WHEN $2='done' THEN NOW() ELSE fulfilled_at END WHERE id=$1 AND status IN ('paid','needs_review')`,
        [req.params.id,status]);
      await c.query('COMMIT');res.json({success:true,status});
    } catch(e){await c.query('ROLLBACK');console.error('queue-ack:',e.message);res.status(500).json({success:false,error:'server_error'});}
    finally{c.release();}
  });
}
module.exports={registerNightmarePayments};
