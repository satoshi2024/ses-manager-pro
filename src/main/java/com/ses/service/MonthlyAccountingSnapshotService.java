package com.ses.service;

/** 月次締め時の帰属snapshotを作成するサービス。既存snapshotは更新しない。 */
public interface MonthlyAccountingSnapshotService {

    int snapshotMonth(String workMonth);
}
