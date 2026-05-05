# Family Guard Email OTP Function

This Appwrite Function sends OTP emails using nodemailer for the Family Guard Android app.

## Setup Instructions

### 1. Deploy the Function to Appwrite

1. **Login to your Appwrite Console**: https://nyc.cloud.appwrite.io/console/project-68937b7c0019703fa03d
2. **Go to Functions** in the left sidebar
3. **Find your existing function** with ID: `6894db02c29104eb3125`
4. **Update the function code**:
   - Copy the contents of `index.js` 
   - Paste it into the function editor
   - Copy the contents of `package.json`
   - Set the runtime to: `Node.js 18`

### 2. Configure Email Settings

You need to set up environment variables in your Appwrite Function:

1. **Go to Settings** tab of your function
2. **Add Environment Variables**:
   ```
   EMAIL_USER = your-gmail-address@gmail.com
   EMAIL_PASS = your-app-password
   ```

### 3. Gmail Setup (Recommended)

1. **Enable 2-Factor Authentication** on your Gmail account
2. **Generate an App Password**:
   - Go to Google Account settings
   - Security → 2-Step Verification → App passwords
   - Generate a new app password for "Mail"
   - Use this password (not your regular password) in `EMAIL_PASS`

### 4. Alternative Email Providers

If you don't want to use Gmail, you can modify the transporter configuration in `index.js`:

#### For Outlook/Hotmail:
```javascript
const transporter = nodemailer.createTransporter({
    service: 'outlook',
    auth: {
        user: process.env.EMAIL_USER,
        pass: process.env.EMAIL_PASS
    }
});
```

#### For Custom SMTP:
```javascript
const transporter = nodemailer.createTransporter({
    host: 'your-smtp-server.com',
    port: 587,
    secure: false,
    auth: {
        user: process.env.EMAIL_USER,
        pass: process.env.EMAIL_PASS
    }
});
```

### 5. Test the Function

1. **Deploy** the function
2. **Test it** using the Appwrite Console test feature
3. **Use this test payload**:
```json
{
    "to": "your-test-email@gmail.com",
    "otp": "123456",
    "userType": "parent",
    "subject": "Test OTP - Family Guard"
}
```

### 6. Verify Android App Integration

The Android app is already configured to call your function with ID `6894db02c29104eb3125`. Once you deploy this function code, it will:

1. **Send real OTP emails** to the email addresses entered in the app
2. **Fall back to development mode** if the function fails
3. **Show detailed logs** for debugging

## Function Features

- ✅ **Professional HTML email template**
- ✅ **OTP expiration notice** (5 minutes)
- ✅ **Security warnings** 
- ✅ **Error handling** and logging
- ✅ **Fallback support** in Android app
- ✅ **Multiple email provider support**

## Troubleshooting

### Common Issues:

1. **"Invalid login" error**: Make sure you're using an App Password, not your regular Gmail password
2. **"Less secure app access" error**: Enable 2-Factor Authentication and use App Passwords
3. **Function timeout**: Increase timeout in Appwrite Function settings
4. **CORS errors**: The function already includes proper headers

### Debug Steps:

1. Check the **Function Logs** in Appwrite Console
2. Test the function directly in **Appwrite Console**
3. Check your **email provider settings**
4. Verify **environment variables** are set correctly

## Next Steps

After deploying this function:

1. **Test the complete flow** in your Android app
2. **Check your email** for the OTP messages  
3. **Monitor the function logs** for any errors
4. **Customize the email template** if needed

The Android app will now send real OTP emails to users! 🎉