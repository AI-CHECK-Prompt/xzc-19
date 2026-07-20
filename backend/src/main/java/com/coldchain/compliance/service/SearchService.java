package com.coldchain.compliance.service;

import com.coldchain.compliance.entity.TransportTask;
import com.coldchain.compliance.entity.DrugBatch;
import com.coldchain.compliance.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 多维检索：按批次/任务/时间区间
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final TransportTaskRepository taskRepo;
    private final DrugBatchRepository batchRepo;

    public Map<String, Object> searchTasks(String taskNo, String status,
                                            OffsetDateTime from, OffsetDateTime to) {
        List<TransportTask> list = taskRepo.search(taskNo, status, from, to);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", list.size());
        r.put("items", list);
        return r;
    }

    public Map<String, Object> searchBatches(String dosageForm, String batchNo) {
        List<DrugBatch> list = new ArrayList<>();
        if (batchNo != null && !batchNo.isEmpty()) {
            batchRepo.findByBatchNo(batchNo).ifPresent(list::add);
        } else if (dosageForm != null && !dosageForm.isEmpty()) {
            list.addAll(batchRepo.findByDosageForm(dosageForm));
        } else {
            list.addAll(batchRepo.findAll());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", list.size());
        r.put("items", list);
        return r;
    }
}
