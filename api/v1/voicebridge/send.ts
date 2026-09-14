export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const text = req.body?.text || '';
  const language = req.body?.language || 'hi';
  const transport = req.body?.transport || 'wifi_direct';
  return res.status(200).json({
    success: true,
    transport,
    message: {
      id: `msg_${Date.now()}`,
      senderId: 'device_vercel_prod',
      language,
      transport,
      text,
      timestamp: Date.now(),
    },
  });
}
