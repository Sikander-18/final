const nodemailer = require('nodemailer');

/**
 * FIXED: Main entry point for Appwrite Function
 * Fixed the nodemailer.createTransporter typo
 */
module.exports = async ({ req, res, log, error }) => {
    try {
        log('🚀 Sentinel Email OTP Function Started (FIXED VERSION)');
        log('📅 Timestamp:', new Date().toISOString());
        
        // Parse request body
        let emailData;
        try {
            const rawBody = req.body;
            emailData = typeof rawBody === 'string' ? JSON.parse(rawBody) : rawBody;
            log('📧 Email request data:', JSON.stringify(emailData));
        } catch (e) {
            error('❌ Failed to parse request body:', e.message);
            return res.json({
                success: false,
                message: 'Invalid request body format'
            }, 400);
        }

        // Validate required fields
        const { to, otp, userType } = emailData;
        if (!to || !otp) {
            error('❌ Missing required fields: to, otp');
            return res.json({
                success: false,
                message: 'Missing required fields: to and otp'
            }, 400);
        }

        log(`📧 Sending OTP ${otp} to ${to} for ${userType} user`);

        // Get email credentials from environment
        const emailUser = process.env.EMAIL_USER;
        const emailPass = process.env.EMAIL_PASS;
        
        log('🔍 Environment check:');
        log('   EMAIL_USER:', emailUser ? `SET (${emailUser})` : 'NOT SET');
        log('   EMAIL_PASS:', emailPass ? 'SET (length: ' + (emailPass?.length || 0) + ')' : 'NOT SET');
        
        if (!emailUser || !emailPass) {
            error('❌ Email credentials not configured');
            return res.json({
                success: false,
                message: 'Email service not configured. Please set EMAIL_USER and EMAIL_PASS environment variables.',
                debug: {
                    emailUser: emailUser ? 'SET' : 'NOT SET',
                    emailPass: emailPass ? 'SET' : 'NOT SET'
                }
            }, 500);
        }

        // Create email transporter (FIXED: removed 'r' from createTransporter)
        let transporter;
        try {
            log('🔧 Creating email transporter (Fixed version)...');
            transporter = nodemailer.createTransport({  // ✅ FIXED: was createTransporter
                service: 'gmail',
                auth: {
                    user: emailUser,
                    pass: emailPass
                }
            });
            
            // Verify connection
            log('🔍 Verifying email connection...');
            await transporter.verify();
            log('✅ Email connection verified successfully');
            
        } catch (e) {
            error('❌ Gmail service failed:', e.message);
            
            // Try alternative SMTP configuration
            try {
                log('🔄 Trying alternative SMTP configuration...');
                transporter = nodemailer.createTransport({  // ✅ FIXED: was createTransporter
                    host: 'smtp.gmail.com',
                    port: 587,
                    secure: false,
                    auth: {
                        user: emailUser,
                        pass: emailPass
                    },
                    tls: {
                        rejectUnauthorized: false
                    }
                });
                
                log('🔍 Verifying SMTP connection...');
                await transporter.verify();
                log('✅ SMTP connection verified successfully');
                
            } catch (altError) {
                error('❌ SMTP configuration also failed:', altError.message);
                
                // Try one more configuration without verification
                try {
                    log('🔄 Trying basic configuration without verification...');
                    transporter = nodemailer.createTransport({  // ✅ FIXED: was createTransporter
                        host: 'smtp.gmail.com',
                        port: 587,
                        secure: false,
                        auth: {
                            user: emailUser,
                            pass: emailPass
                        },
                        tls: {
                            rejectUnauthorized: false
                        },
                        debug: true
                    });
                    log('✅ Basic transporter created (skipped verification)');
                    
                } catch (finalError) {
                    error('❌ All transporter configurations failed:', finalError.message);
                    return res.json({
                        success: false,
                        message: 'Email service configuration error: ' + finalError.message,
                        debug: {
                            gmailError: e.message,
                            smtpError: altError.message,
                            finalError: finalError.message
                        }
                    }, 500);
                }
            }
        }

        // Create email content
        const emailSubject = `Your OTP Code - Sentinel`;
        const emailHtml = `
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Family Guard OTP</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 0; padding: 20px; background-color: #f5f5f5; }
                    .container { max-width: 600px; margin: 0 auto; background-color: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                    .header { text-align: center; color: #2196F3; margin-bottom: 30px; }
                    .otp-code { font-size: 32px; font-weight: bold; color: #4CAF50; text-align: center; background-color: #f8f9fa; padding: 20px; border-radius: 8px; margin: 20px 0; letter-spacing: 4px; }
                    .info { color: #666; margin: 20px 0; }
                    .footer { margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee; color: #999; font-size: 12px; text-align: center; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>🔐 Sentinel</h1>
                        <h2>Email Verification</h2>
                    </div>
                    
                    <p>Hello,</p>
                    <p>You have requested an OTP code for ${userType} account verification. Please use the following code:</p>
                    
                    <div class="otp-code">${otp}</div>
                    
                    <div class="info">
                        <p><strong>Important:</strong></p>
                        <ul>
                            <li>This OTP is valid for <strong>5 minutes</strong> only</li>
                            <li>Do not share this code with anyone</li>
                            <li>If you didn't request this code, please ignore this email</li>
                        </ul>
                    </div>
                    
                    <p>Enter this code in your Sentinel app to complete the verification process.</p>
                    
                    <div class="footer">
                        <p>This email was sent automatically by Sentinel App</p>
                        <p>Time: ${new Date().toLocaleString()}</p>
                        <p>Function: Fixed Version v1.1</p>
                    </div>
                </div>
            </body>
            </html>
        `;

        // Send email
        const mailOptions = {
            from: `"Sentinel App" <${emailUser}>`,
            to: to,
            subject: emailSubject,
            html: emailHtml,
            text: `Your Sentinel OTP code is: ${otp}. Valid for 5 minutes. Do not share this code. Time: ${new Date().toLocaleString()}`
        };

        try {
            log('📤 Attempting to send email...');
            log('📧 To:', to);
            log('📧 From:', emailUser);
            log('📧 Subject:', emailSubject);
            
            const info = await transporter.sendMail(mailOptions);
            
            log('🎉 EMAIL SENT SUCCESSFULLY!');
            log('📧 Message ID:', info.messageId);
            log('📬 Response:', info.response);
            log('📨 Envelope:', JSON.stringify(info.envelope));
            
            return res.json({
                success: true,
                message: `🎉 OTP email sent successfully to ${to}`,
                timestamp: new Date().toISOString(),
                messageId: info.messageId,
                envelope: info.envelope
            });
            
        } catch (emailError) {
            error('❌ Failed to send email:', emailError.message);
            error('📧 Error code:', emailError.code);
            error('📧 Error command:', emailError.command);
            
            return res.json({
                success: false,
                message: 'Failed to send email: ' + emailError.message,
                error: emailError.message,
                debug: {
                    code: emailError.code,
                    command: emailError.command,
                    response: emailError.response
                }
            }, 500);
        }

    } catch (e) {
        error('❌ Function execution failed:', e.message);
        error('📍 Stack trace:', e.stack);
        
        return res.json({
            success: false,
            message: 'Function execution failed: ' + e.message,
            error: e.message
        }, 500);
    }
};