const nodemailer = require('nodemailer');

/**
 * Debug version of Appwrite Function to send OTP emails
 * This version provides detailed logging to identify email delivery issues
 */
module.exports = async ({ req, res, log, error }) => {
    try {
        log('🚀 === EMAIL DEBUG FUNCTION STARTED ===');
        log('📅 Timestamp:', new Date().toISOString());
        
        // Environment variable check
        const emailUser = process.env.EMAIL_USER;
        const emailPass = process.env.EMAIL_PASS;
        
        log('🔍 Environment Variables Check:');
        log('   EMAIL_USER:', emailUser ? `SET (${emailUser})` : 'NOT SET');
        log('   EMAIL_PASS:', emailPass ? `SET (${emailPass.substring(0, 4)}****)` : 'NOT SET');
        
        // Parse request body
        let emailData;
        try {
            const rawBody = req.body;
            log('📥 Raw request body type:', typeof rawBody);
            log('📥 Raw request body:', rawBody);
            
            emailData = typeof rawBody === 'string' ? JSON.parse(rawBody) : rawBody;
            log('📧 Parsed email data:', JSON.stringify(emailData, null, 2));
        } catch (e) {
            error('❌ Failed to parse request body:', e.message);
            return res.json({
                success: false,
                message: 'Invalid request body format',
                debug: {
                    bodyType: typeof req.body,
                    bodyContent: req.body
                }
            }, 400);
        }

        // Validate fields
        const { to, otp, userType } = emailData;
        log('📝 Email Details:');
        log('   To:', to);
        log('   OTP:', otp);
        log('   User Type:', userType);
        
        if (!to || !otp) {
            error('❌ Missing required fields');
            return res.json({
                success: false,
                message: 'Missing required fields: to and otp',
                received: { to, otp, userType }
            }, 400);
        }

        // Check environment variables
        if (!emailUser || !emailPass) {
            error('❌ Email credentials not configured');
            return res.json({
                success: false,
                message: 'Email credentials not configured',
                debug: {
                    emailUser: emailUser ? 'SET' : 'NOT SET',
                    emailPass: emailPass ? 'SET' : 'NOT SET',
                    instructions: [
                        '1. Go to your Appwrite function settings',
                        '2. Add EMAIL_USER environment variable with your Gmail address',
                        '3. Add EMAIL_PASS environment variable with your Gmail App Password',
                        '4. Make sure you have 2FA enabled and generated an App Password'
                    ]
                }
            }, 500);
        }

        // Test multiple email configurations
        const configurations = [
            {
                name: 'Gmail Service',
                config: {
                    service: 'gmail',
                    auth: { user: emailUser, pass: emailPass }
                }
            },
            {
                name: 'Gmail SMTP',
                config: {
                    host: 'smtp.gmail.com',
                    port: 587,
                    secure: false,
                    auth: { user: emailUser, pass: emailPass },
                    tls: { rejectUnauthorized: false }
                }
            },
            {
                name: 'Gmail SMTP SSL',
                config: {
                    host: 'smtp.gmail.com',
                    port: 465,
                    secure: true,
                    auth: { user: emailUser, pass: emailPass }
                }
            }
        ];

        let emailSent = false;
        let lastError = null;

        for (const { name, config } of configurations) {
            try {
                log(`🔧 Trying ${name} configuration...`);
                
                const transporter = nodemailer.createTransporter(config);
                
                // Test connection
                log(`🔍 Testing ${name} connection...`);
                await transporter.verify();
                log(`✅ ${name} connection verified`);

                // Send test email
                const mailOptions = {
                    from: `"Family Guard App" <${emailUser}>`,
                    to: to,
                    subject: `Your OTP Code - Family Guard (${name})`,
                    html: `
                        <div style="font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5;">
                            <div style="max-width: 600px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px;">
                                <h1 style="color: #2196F3; text-align: center;">🔐 Family Guard</h1>
                                <h2 style="text-align: center;">Email Verification</h2>
                                
                                <p>Hello,</p>
                                <p>Your OTP code for ${userType} verification is:</p>
                                
                                <div style="font-size: 32px; font-weight: bold; color: #4CAF50; text-align: center; background: #f8f9fa; padding: 20px; border-radius: 8px; margin: 20px 0; letter-spacing: 4px;">
                                    ${otp}
                                </div>
                                
                                <p><strong>Important:</strong></p>
                                <ul>
                                    <li>This OTP is valid for 5 minutes only</li>
                                    <li>Do not share this code with anyone</li>
                                </ul>
                                
                                <hr style="margin: 30px 0; border: 1px solid #eee;">
                                <p style="font-size: 12px; color: #999; text-align: center;">
                                    Sent via ${name}<br>
                                    Time: ${new Date().toLocaleString()}<br>
                                    From: Family Guard App
                                </p>
                            </div>
                        </div>
                    `,
                    text: `Your Family Guard OTP: ${otp}. Valid for 5 minutes. Sent via ${name} at ${new Date().toLocaleString()}`
                };

                log(`📤 Sending email via ${name}...`);
                const info = await transporter.sendMail(mailOptions);
                
                log(`✅ Email sent successfully via ${name}!`);
                log('📧 Message ID:', info.messageId);
                log('📬 Response:', info.response);
                
                emailSent = true;
                
                return res.json({
                    success: true,
                    message: `OTP email sent successfully to ${to} via ${name}`,
                    debug: {
                        method: name,
                        messageId: info.messageId,
                        response: info.response,
                        timestamp: new Date().toISOString()
                    }
                });

            } catch (configError) {
                error(`❌ ${name} failed:`, configError.message);
                lastError = configError;
                log(`🔄 Trying next configuration...`);
            }
        }

        // If all configurations failed
        error('❌ All email configurations failed');
        return res.json({
            success: false,
            message: 'All email sending methods failed',
            debug: {
                lastError: lastError.message,
                errorCode: lastError.code,
                configurations: configurations.map(c => c.name),
                suggestions: [
                    'Check if 2-Factor Authentication is enabled on Gmail',
                    'Verify App Password is correct (16 characters, no spaces)',
                    'Try using your actual Gmail password temporarily for testing',
                    'Check if "Less secure app access" needs to be enabled',
                    'Try a different email provider (Outlook, Yahoo, etc.)'
                ]
            }
        }, 500);

    } catch (e) {
        error('❌ Function execution failed:', e.message);
        error('Stack trace:', e.stack);
        
        return res.json({
            success: false,
            message: 'Function execution failed: ' + e.message,
            error: e.message,
            stack: e.stack
        }, 500);
    }
};