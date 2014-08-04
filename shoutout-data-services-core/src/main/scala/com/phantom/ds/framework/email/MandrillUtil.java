package com.phantom.ds.framework.email;

import com.microtripit.mandrillapp.lutung.MandrillApi;
import com.microtripit.mandrillapp.lutung.model.MandrillApiError;
import com.microtripit.mandrillapp.lutung.view.MandrillMessage;
import com.microtripit.mandrillapp.lutung.view.MandrillMessageStatus;
import com.phantom.ds.DSConfiguration;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by aparrish on 2/22/14.
 */
public class MandrillUtil {


    public static void sendMailViaMandrill(
            MandrillConfiguration configuration,
            String recipientEmail, String newPassword ) throws IOException, MandrillApiError {

        MandrillApi mandrillApi  = new MandrillApi(configuration.getApiKey());

        MandrillMessage message = new MandrillMessage();
        message.setSubject("Sneeky Password Assistance");
        message.setHtml("Subject: \n" +
                "Sneeky Password Assistance\n" +
                "\n" +
                "Body: \n" +
                "Hi,\n" +
                "\n" +
                "We received a notice that you forgot your password. Please use the password below as your new one:\n" +
                "\n" +
                newPassword + "\n" +
                "\n" +
                "Please email hello@sneekyapp.com if you have any further questions.\n" +
                "\n" +
                "- Sneeky Team\n");
        message.setAutoText(true);
        message.setFromEmail(configuration.getUsername());
        message.setFromName("Team Sneeky");


        ArrayList<MandrillMessage.Recipient> recipients = new ArrayList<MandrillMessage.Recipient>();
        MandrillMessage.Recipient recipient = new MandrillMessage.Recipient();
        recipient.setEmail(recipientEmail);
        recipients.add(recipient);

        message.setPreserveRecipients(true);
        message.setTo(recipients);

        ArrayList<String> tags = new ArrayList<String>();
        tags.add("forgotPassword");
        message.setTags(tags);

        try {
            MandrillMessageStatus[] messageStatusReports = mandrillApi.messages().send(message, false);
        } catch(final MandrillApiError e) {

            System.out.print(e.toString());

        }
    }

}
