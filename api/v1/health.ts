export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  return res.status(200).json({
    status: 'healthy',
    service: 'iTantra Semantic Transceiver API (Vercel Serverless)',
    version: '1.0.0',
    models_status: {
      stt: 'ready',
      tts: 'ready',
      tokenizer: 'ready',
    },
  });
}
