package com.example.master2;

import android.os.Bundle;
import android.view.View;
import android.widget.ExpandableListView;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.master2.adapters.GuideExpandableListAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class GuideBookActivity extends AppCompatActivity {

        GuideExpandableListAdapter listAdapter;
        ExpandableListView expListView;
        List<String> listDataHeader;
        HashMap<String, String> listDataChild;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                setContentView(R.layout.activity_guide_book);

                // Initialize views
                expListView = findViewById(R.id.expandableListView);
                ImageView btnBack = findViewById(R.id.btnBack);

                // Prepare list data
                prepareListData();

                // Create adapter
                listAdapter = new GuideExpandableListAdapter(this, listDataHeader, listDataChild);

                // Set adapter
                expListView.setAdapter(listAdapter);

                // Back button
                btnBack.setOnClickListener(v -> finish());

                // Expand first group by default for better UX
                // expListView.expandGroup(0);
        }

        private void prepareListData() {
                listDataHeader = new ArrayList<>();
                listDataChild = new HashMap<>();

                // Adding headers
                listDataHeader.add("How to Add Child");
                listDataHeader.add("What is Detailed Stats");
                listDataHeader.add("What is App Limits");
                listDataHeader.add("What is Bell Icon on the Dashboard");
                listDataHeader.add("What is Timer Page");
                listDataHeader.add("What is on Settings Page");

                // Adding content (Exact text requested by user)

                // 1. How to Add Child
                String p1 = "PARENT SIDE:\n" +
                                "Click on Manage Device or Add Button to open QR code so that child could scan QR.\n\n"
                                +
                                "CHILD SIDE:\n" +
                                "Enter Name, Grant Permissions asked, and then scan the QR of the parent that wants to connect to the child.";

                // 2. Detailed Stats
                String p2 = "In basic works, it shows 7 days usage data of child device.\n" +
                                "You can see exactly how much time was spent on each app daily.";

                // 3. App Limits
                String p3 = "TIMER:\n" +
                                "To set timer on child device: Set Limit, Add Time. The timer will run on those apps until you remove it. "
                                +
                                "It applies everyday for the amount of time you set it.\n\n" +
                                "NOTE - EVERYDAY AS THE TIMER EXPIRES IT WON'T BLOCK APPS BUT YOU CAN SEE THAT TIMER IS EXPIRED AND BLOCK THOSE APPS.\n\n"
                                +
                                "BLOCKING:\n" +
                                "Simply select apps to block and they will be blocked until you unblock it.";

                // 4. Bell Icon
                String p4 = "PERMISSION STATUS:\n" +
                                "Gives real time status of permission of child device as if they are enabled or disabled. "
                                +
                                "This helps you know if permissions are running as they are the mandatory thing to make our service work.\n\n"
                                +
                                "APP STATUS:\n" +
                                "You can know which apps are installed and uninstalled on the child device in real time with date and time.";

                // 5. Timer Page
                String p5 = "Timer page shows all the apps you set timer on and how much time is left before time expires.";

                // 6. Settings Page
                String p6 = "UNINSTALL PROTECTION BUTTON:\n" +
                                "This powerful feature prevents the app from being removed by the child.\n\n" +
                                "WHY IS IT NEEDED?\n" +
                                "To ensure your parental controls remain active and cannot be bypassed.\n\n" +
                                "IMPORTANT:\n" +
                                "To uninstall the app yourself, you MUST specifically tap this button to 'Deactivate' protection first.\n\n"
                                +
                                "Also contains your Details and our Terms and Conditions.";

                // Map Header -> Content
                listDataChild.put(listDataHeader.get(0), p1);
                listDataChild.put(listDataHeader.get(1), p2);
                listDataChild.put(listDataHeader.get(2), p3);
                listDataChild.put(listDataHeader.get(3), p4);
                listDataChild.put(listDataHeader.get(4), p5);
                listDataChild.put(listDataHeader.get(5), p6);
        }
}
