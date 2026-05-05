const nodemailer = require('nodemailer');

/**
 * Simple and reliable Appwrite Function to send OTP emails
 * Uses basic SMTP configuration that works with most email providers
 */
module.exports = async ({ req, res, log, error }) => {
    try {
        log('📧 Simple Email Function Started');
        
        // Parse request
        const emailData = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
        const { to, otp, userType } = emailData;
        
        log('📧 Sending OTP to:', to);
        
        // Email credentials from environment
        const EMAIL_USER = process.env.EMAIL_USER;
        const EMAIL_PASS = process.env.EMAIL_PASS;
        
        if (!EMAIL_USER || !EMAIL_PASS) {
            log('❌ Email credentials not set');
            return res.json({ success: false, message: 'Email not configured' }, 500);
        }
        
        // Simple transporter configuration
        const transporter = nodemailer.createTransporter({
            host: 'smtp.gmail.com',
            port: 587,
            secure: false, // true for 465, false for other ports
            auth: {
                user: EMAIL_USER,
                pass: EMAIL_PASS,
            },
            tls: {
                rejectUnauthorized: false
            }
        });
        
        // Simple email content
        const mailOptions = {
            from: EMAIL_USER,
            to: to,
            subject: 'Your Family Guard OTP Code',
            html: `
                <h2>Family Guard - OTP Verification</h2>
                <p>Your verification code is: <strong style="font-size: 24px; color: #007bff;">${otp}</strong></p>
                <p>This code will expire in 5 minutes.</p>
                <p>If you didn't request this, please ignore this email.</p>
                <hr>
                <small>Sent at ${new Date().toLocaleString()}</small>
            `
        };
        
        // Send email
        const info = await transporter.sendMail(mailOptions);
        log('✅ Email sent:', info.messageId);
        
        return res.json({
            success: true,
            message: 'Email sent successfully',
            messageId: info.messageId
        });
        
    } catch (e) {
        error('❌ Error:', e.message);
        return res.json({
            success: false,
            message: e.message
        }, 500);
    }
};