export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const action = req.query?.action as string;

  if (action === 'peers') {
    return res.status(200).json([
      {
        id: 'responder_mesh_01',
        name: 'Field Responder Mesh (Alpha)',
        addresses: ['192.168.49.2'],
        port: 8988,
        transports: ['wifi_direct', 'wifi_lan', 'bluetooth'],
        lastSeen: Date.now(),
      },
      {
        id: 'base_station_gateway',
        name: 'Base Station Relay (Emergency Command)',
        addresses: ['10.42.0.1'],
        port: 8990,
        transports: ['wifi_lan'],
        lastSeen: Date.now() - 4500,
      }
    ]);
  }

  if (action === 'connect-peer') {
    const transport = req.body?.transport || 'wifi_direct';
    return res.status(200).json({
      success: true,
      activeTransport: transport,
      state: 'CONNECTED',
    });
  }

  if (action === 'quick-connect-local') {
    const transport = req.body?.transport || 'wifi_direct';
    return res.status(200).json({
      success: true,
      activeTransport: transport,
      state: 'CONNECTED',
    });
  }

  if (action === 'disconnect') {
    return res.status(200).json({
      success: true,
      state: 'DISCONNECTED',
    });
  }

  if (action === 'send') {
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

  return res.status(200).json({
    status: 'ok',
    action,
    service: 'Voice Bridge Vercel Serverless',
  });
}
