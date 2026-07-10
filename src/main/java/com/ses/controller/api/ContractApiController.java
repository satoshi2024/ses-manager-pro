package com.ses.controller.api;

import com.ses.entity.Contract;
import com.ses.service.ContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 契約APIコントローラー
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ContractApiController {

    private final ContractService contractService;

    /**
     * 契約一覧取得
     *
     * @return 契約リスト
     */
    @GetMapping
    public ResponseEntity<List<Contract>> list() {
        return ResponseEntity.ok(contractService.list());
    }

    /**
     * 契約詳細取得
     *
     * @param id 契約ID
     * @return 契約情報
     */
    @GetMapping("/{id}")
    public ResponseEntity<Contract> getById(@PathVariable Long id) {
        return ResponseEntity.ok(contractService.getById(id));
    }

    /**
     * 契約登録
     *
     * @param contract 契約情報
     * @return 結果
     */
    @PostMapping
    public ResponseEntity<Boolean> create(@RequestBody Contract contract) {
        return ResponseEntity.ok(contractService.save(contract));
    }

    /**
     * 契約更新
     *
     * @param id 契約ID
     * @param contract 契約情報
     * @return 結果
     */
    @PutMapping("/{id}")
    public ResponseEntity<Boolean> update(@PathVariable Long id, @RequestBody Contract contract) {
        contract.setId(id);
        return ResponseEntity.ok(contractService.updateById(contract));
    }

    /**
     * 契約削除
     *
     * @param id 契約ID
     * @return 結果
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Boolean> delete(@PathVariable Long id) {
        return ResponseEntity.ok(contractService.removeById(id));
    }
}
