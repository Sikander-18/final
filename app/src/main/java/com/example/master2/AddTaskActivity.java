package com.example.master2;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Calendar;

public class AddTaskActivity extends AppCompatActivity {

    private EditText etTaskTitle, etTaskDescription;
    private TextView tvSelectedTime, tvSelectedDate;
    private Button btnSelectTime, btnSelectDate, btnSaveTask;
    private ImageButton btnBack;
    
    private String selectedTime = "";
    private String selectedDate = "";
    private Task editingTask = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_task);

        initViews();
        setupClickListeners();
        
        // Check if editing existing task
        Intent intent = getIntent();
        if (intent.hasExtra("task")) {
            editingTask = (Task) intent.getSerializableExtra("task");
            loadTaskForEditing();
        }
        
        setDefaultDate();
    }

    private void initViews() {
        etTaskTitle = findViewById(R.id.etTaskTitle);
        etTaskDescription = findViewById(R.id.etTaskDescription);
        tvSelectedTime = findViewById(R.id.tvSelectedTime);
        tvSelectedDate = findViewById(R.id.tvSelectedDate);
        btnSelectTime = findViewById(R.id.btnSelectTime);
        btnSelectDate = findViewById(R.id.btnSelectDate);
        btnSaveTask = findViewById(R.id.btnSaveTask);
        btnBack = findViewById(R.id.btnBack);
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());
        
        btnSelectTime.setOnClickListener(v -> showTimePicker());
        btnSelectDate.setOnClickListener(v -> showDatePicker());
        
        btnSaveTask.setOnClickListener(v -> saveTask());
    }

    private void showTimePicker() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        TimePickerDialog timePickerDialog = new TimePickerDialog(this,
            (view, hourOfDay, selectedMinute) -> {
                selectedTime = String.format("%02d:%02d", hourOfDay, selectedMinute);
                tvSelectedTime.setText("Selected: " + selectedTime);
            }, hour, minute, true);
        timePickerDialog.show();
    }

    private void showDatePicker() {
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH);
        int day = calendar.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog datePickerDialog = new DatePickerDialog(this,
            (view, selectedYear, selectedMonth, selectedDay) -> {
                selectedDate = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay);
                tvSelectedDate.setText("Selected: " + selectedDate);
            }, year, month, day);
        datePickerDialog.show();
    }

    private void setDefaultDate() {
        Calendar calendar = Calendar.getInstance();
        selectedDate = String.format("%04d-%02d-%02d", 
            calendar.get(Calendar.YEAR), 
            calendar.get(Calendar.MONTH) + 1, 
            calendar.get(Calendar.DAY_OF_MONTH));
        tvSelectedDate.setText("Selected: " + selectedDate);
    }

    private void loadTaskForEditing() {
        if (editingTask != null) {
            etTaskTitle.setText(editingTask.getTitle());
            etTaskDescription.setText(editingTask.getDescription());
            selectedTime = editingTask.getTime();
            selectedDate = editingTask.getDate();
            tvSelectedTime.setText("Selected: " + selectedTime);
            tvSelectedDate.setText("Selected: " + selectedDate);
            btnSaveTask.setText("Update Task");
        }
    }

    private void saveTask() {
        String title = etTaskTitle.getText().toString().trim();
        String description = etTaskDescription.getText().toString().trim();

        if (title.isEmpty()) {
            Toast.makeText(this, "Please enter task title", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedTime.isEmpty()) {
            Toast.makeText(this, "Please select time", Toast.LENGTH_SHORT).show();
            return;
        }

        Task task = new Task(title, selectedTime, description, false, selectedDate);
        
        Intent resultIntent = new Intent();
        resultIntent.putExtra("task", task);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
