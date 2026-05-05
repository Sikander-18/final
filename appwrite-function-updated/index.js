const nodemailer = require('nodemailer');

/**
 * Updated Appwrite Function to send OTP emails
 * This function receives email data and sends OTP emails using nodemailer
 */
module.exports = async ({ req, res, log, error }) => {
    try {
        log('📧 Email OTP Function started');
        log('🔍 Environment check - EMAIL_USER:', process.env.EMAIL_USER ? 'SET' : 'NOT SET');
        log('🔍 Environment check - EMAIL_PASS:', process.env.EMAIL_PASS ? 'SET' : 'NOT SET');
        
        // Parse the request body
        let emailData;
        try {
            emailData = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
            log('📥 Received email data:', JSON.stringify(emailData));
        } catch (e) {
            error('❌ Failed to parse request body:', e.message);
            return res.json({
                success: false,
                message: 'Invalid request body format'
            }, 400);
        }

        // Validate required fields
        const { to, otp, userType, subject } = emailData;
        if (!to || !otp) {
            error('❌ Missing required fields: to, otp');
            return res.json({
                success: false,
                message: 'Missing required fields: to and otp'
            }, 400);
        }

        log(`📧 Sending OTP ${otp} to ${to} for ${userType} user`);

        // Check if environment variables are set
        const emailUser = process.env.EMAIL_USER;
        const emailPass = process.env.EMAIL_PASS;
        
        if (!emailUser || !emailPass) {
            error('❌ Email credentials not configured');
            log('📋 Please set the following environment variables in your function:');
            log('   EMAIL_USER = your-email@gmail.com');
            log('   EMAIL_PASS = your-app-password');
            
            return res.json({
                success: false,
                message: 'Email service not configured. Please set EMAIL_USER and EMAIL_PASS environment variables.',
                debug: {
                    emailUser: emailUser ? 'SET' : 'NOT SET',
                    emailPass: emailPass ? 'SET' : 'NOT SET'
                }
            }, 500);
        }

        // Configure email transporter
        let transporter;
        try {
            log('🔧 Configuring email transporter...');
            transporter = nodemailer.createTransporter({
                service: 'gmail',
                auth: {
                    user: emailUser,
                    pass: emailPass
                },
                debug: true, // Enable debug logging
                logger: true // Enable logger
            });
            
            // Verify transporter configuration
            log('🔍 Verifying transporter configuration...');
            await transporter.verify();
            log('✅ Email transporter verified successfully');
            
        } catch (e) {
            error('❌ Email transporter configuration failed:', e.message);
            return res.json({
                success: false,
                message: 'Email service configuration error: ' + e.message,
                error: e.message
            }, 500);
        }

        // Email template
        const emailSubject = subject || `Your OTP Code - Sentinel`;
        const emailHtml = `
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
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
                        <p>This email was sent automatically by Sentinel: The parental monitoring application</p>
                        <p>Time: ${new Date().toLocaleString()}</p>
                    </div>
                </div>
            </body>
            </html>
        `;

        // Email options
        const mailOptions = {
            from: `"Sentinel: The parental monitoring application" <${emailUser}>`,
            to: to,
            subject: emailSubject,
            html: emailHtml,
            text: `Your Sentinel OTP code is: ${otp}. Valid for 5 minutes. Do not share this code.`
        };

        // Send email
        try {
            log('📤 Attempting to send email to:', to);
            log('📧 Email subject:', emailSubject);
            
            const info = await transporter.sendMail(mailOptions);
            
            log('✅ Email sent successfully!');
            log('📧 Message ID:', info.messageId);
            log('📬 Response:', info.response);
            
            return res.json({
                success: true,
                message: `OTP email sent successfully to ${to}`,
                timestamp: new Date().toISOString(),
                messageId: info.messageId
            });
            
        } catch (emailError) {
            error('❌ Failed to send email:', emailError.message);
            error('📧 Email error details:', emailError);
            
            return res.json({
                success: false,
                message: 'Failed to send email: ' + emailError.message,
                error: emailError.message,
                debug: {
                    code: emailError.code,
                    command: emailError.command
                }
            }, 500);
        }

    } catch (e) {
        error('❌ Function execution failed:', e.message);
        error('Stack trace:', e.stack);
        
        return res.json({
            success: false,
            message: 'Function execution failed: ' + e.message,
            error: e.message
        }, 500);
    }
};