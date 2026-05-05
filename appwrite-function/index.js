const nodemailer = require('nodemailer');

/**
 * Appwrite Function to send OTP emails
 * This function receives email data and sends OTP emails using nodemailer
 */
module.exports = async ({ req, res, log, error }) => {
    try {
        log('📧 Email OTP Function started');
        
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

        // Configure your email service here
        // For Gmail, you need to:
        // 1. Enable 2-Factor Authentication
        // 2. Generate an App Password
        // 3. Use the App Password instead of your regular password
        const transporter = nodemailer.createTransporter({
            service: 'gmail', // or 'smtp' for custom SMTP
            auth: {
                user: process.env.EMAIL_USER || 'your-email@gmail.com', // Your email
                pass: process.env.EMAIL_PASS || 'your-app-password'     // Your app password
            }
        });

        // Email template
        const emailSubject = subject || `Your OTP Code - Family Guard`;
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
                    .warning { color: #ff5722; font-weight: bold; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>🔐 Family Guard</h1>
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
                    
                    <p>Enter this code in your Family Guard app to complete the verification process.</p>
                    
                    <div class="footer">
                        <p>This email was sent automatically by Family Guard App</p>
                        <p>If you have any questions, please contact our support team</p>
                    </div>
                </div>
            </body>
            </html>
        `;

        // Send email
        const mailOptions = {
            from: process.env.EMAIL_USER || 'your-email@gmail.com',
            to: to,
            subject: emailSubject,
            html: emailHtml
        };

        log('📤 Attempting to send email...');
        await transporter.sendMail(mailOptions);
        
        log(`✅ Email sent successfully to ${to}`);
        
        return res.json({
            success: true,
            message: `OTP email sent successfully to ${to}`,
            timestamp: new Date().toISOString()
        });

    } catch (e) {
        error('❌ Function execution failed:', e.message);
        error('Stack trace:', e.stack);
        
        return res.json({
            success: false,
            message: 'Failed to send email: ' + e.message,
            error: e.message
        }, 500);
    }
};