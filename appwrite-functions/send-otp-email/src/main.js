/**
 * Appwrite Function to send OTP emails
 * This function will be deployed to your Appwrite instance
 * Function ID: send-otp-email
 */

const nodemailer = require('nodemailer');

// Email configuration - Add these as environment variables in your Appwrite function
const EMAIL_USER = process.env.EMAIL_USER || 'your-email@gmail.com';
const EMAIL_PASS = process.env.EMAIL_PASS || 'your-app-password';
const EMAIL_SERVICE = process.env.EMAIL_SERVICE || 'gmail';

// Create transporter
const transporter = nodemailer.createTransport({
    service: EMAIL_SERVICE,
    auth: {
        user: EMAIL_USER,
        pass: EMAIL_PASS
    }
});

// HTML email template
function generateOTPEmailTemplate(otp, userType, expirationMinutes) {
    return `
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Sentinel OTP Verification</title>
        <style>
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                line-height: 1.6;
                color: #333;
                max-width: 600px;
                margin: 0 auto;
                padding: 20px;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            }
            .container {
                background: rgba(255, 255, 255, 0.95);
                border-radius: 20px;
                padding: 40px;
                box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
                backdrop-filter: blur(10px);
            }
            .header {
                text-align: center;
                margin-bottom: 30px;
            }
            .logo {
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                color: white;
                width: 80px;
                height: 80px;
                border-radius: 20px;
                display: inline-flex;
                align-items: center;
                justify-content: center;
                font-size: 32px;
                margin-bottom: 20px;
            }
            .title {
                color: #333;
                font-size: 28px;
                font-weight: 700;
                margin: 0;
            }
            .subtitle {
                color: #666;
                font-size: 16px;
                margin: 10px 0 0 0;
            }
            .otp-container {
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                border-radius: 16px;
                padding: 30px;
                text-align: center;
                margin: 30px 0;
            }
            .otp-code {
                font-size: 36px;
                font-weight: 700;
                color: white;
                letter-spacing: 8px;
                margin: 0;
                text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
            }
            .otp-label {
                color: rgba(255, 255, 255, 0.9);
                font-size: 14px;
                margin: 10px 0 0 0;
                text-transform: uppercase;
                letter-spacing: 1px;
            }
            .content {
                text-align: center;
                margin: 30px 0;
            }
            .warning {
                background: #fff3cd;
                border: 1px solid #ffeaa7;
                border-radius: 12px;
                padding: 20px;
                margin: 20px 0;
            }
            .warning-icon {
                font-size: 24px;
                margin-bottom: 10px;
            }
            .footer {
                text-align: center;
                color: #666;
                font-size: 14px;
                margin-top: 40px;
                padding-top: 20px;
                border-top: 1px solid #eee;
            }
            .user-badge {
                display: inline-block;
                background: rgba(102, 126, 234, 0.1);
                color: #667eea;
                padding: 8px 16px;
                border-radius: 20px;
                font-size: 14px;
                font-weight: 600;
                margin: 10px 0;
            }
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <div class="logo">🛡️</div>
                <h1 class="title">Sentinel</h1>
                <p class="subtitle">Secure OTP Verification</p>
                <div class="user-badge">${userType.toUpperCase()} LOGIN</div>
            </div>
            
            <div class="content">
                <p>You requested an OTP to access your Sentinel account. Use the code below to complete your login:</p>
                
                <div class="otp-container">
                    <div class="otp-code">${otp}</div>
                    <div class="otp-label">Your verification code</div>
                </div>
                
                <p><strong>This code will expire in ${expirationMinutes} minutes.</strong></p>
            </div>
            
            <div class="warning">
                <div class="warning-icon">🔐</div>
                <strong>Security Notice:</strong>
                <p>Never share this code with anyone. Sentinel will never ask for your OTP via phone or email.</p>
            </div>
            
            <div class="footer">
                <p>If you didn't request this code, please ignore this email.</p>
                <p><strong>Sentinel</strong> - The parental monitoring application</p>
                <p style="font-size: 12px; color: #999;">This is an automated message, please do not reply.</p>
            </div>
        </div>
    </body>
    </html>
    `;
}

// Main function
module.exports = async ({ req, res, log, error }) => {
    try {
        log('OTP Email function started');
        log('Request method: ' + req.method);
        log('Request headers: ' + JSON.stringify(req.headers));
        log('Raw request body: ' + JSON.stringify(req.body));
        log('Request body type: ' + typeof req.body);
        
        // Parse request data - handle both direct body and wrapped body
        let requestData;
        if (typeof req.body === 'string') {
            try {
                requestData = JSON.parse(req.body);
            } catch (e) {
                requestData = req.body;
            }
        } else {
            requestData = req.body;
        }
        
        // If the data is wrapped in a body field (from Android HTTP call), unwrap it
        if (requestData.body && typeof requestData.body === 'string') {
            try {
                requestData = JSON.parse(requestData.body);
            } catch (e) {
                log('Failed to parse nested body, using original');
            }
        }
        
        log('Parsed request data: ' + JSON.stringify(requestData));
        
        const { to, otp, userType, expirationMinutes } = requestData;
        
        // Validate required fields
        if (!to || !otp || !userType) {
            error('Missing required fields: to, otp, userType');
            return res.json({
                success: false,
                message: 'Missing required fields'
            }, 400);
        }
        
        // Validate email format
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        if (!emailRegex.test(to)) {
            error('Invalid email format: ' + to);
            return res.json({
                success: false,
                message: 'Invalid email format'
            }, 400);
        }
        
        log(`Sending OTP email to: ${to}, userType: ${userType}, OTP: ${otp}`);
        log(`Email config - User: ${EMAIL_USER}, Service: ${EMAIL_SERVICE}`);
        
        // Email options
        const mailOptions = {
            from: {
                name: 'Sentinel',
                address: EMAIL_USER
            },
            to: to,
            subject: `🛡️ Your Sentinel OTP: ${otp}`,
            html: generateOTPEmailTemplate(otp, userType, expirationMinutes || 5),
            text: `Your Sentinel OTP is: ${otp}. This code will expire in ${expirationMinutes || 5} minutes. Never share this code with anyone.`
        };
        
        log('Attempting to send email...');
        
        // Send email
        const info = await transporter.sendMail(mailOptions);
        
        log(`Email sent successfully: ${info.messageId}`);
        
        return res.json({
            success: true,
            message: 'OTP email sent successfully',
            messageId: info.messageId,
            timestamp: new Date().toISOString()
        });
        
    } catch (err) {
        error('Failed to send OTP email: ' + err.message);
        log('Full error: ' + JSON.stringify(err));
        
        return res.json({
            success: false,
            message: 'Failed to send email',
            error: err.message
        }, 500);
    }
};