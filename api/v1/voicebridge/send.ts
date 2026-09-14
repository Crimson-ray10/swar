export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const text = req.body?.text || '';
  const language = req.body?.language || 'hi';
  return res.status(200).json({
    success: true,
    message: {
      id: `msg_${Date.now()}`,
      senderId: 'device_vercel_prod',
      language,
      text,
      timestamp: Date.now(),
    },
  });
}
