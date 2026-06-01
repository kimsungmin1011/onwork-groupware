package kr.onwork.hr.dto;

import kr.onwork.hr.domain.Salary;

/** 급여 명세(마이페이지). net은 세전에서 약 9% 공제(4대보험·소득세) 추정치. */
public record SalaryResponse(
        int basePay,
        int mealAllowance,
        int transportAllowance,
        int positionAllowance,
        int gross,
        int estimatedNet,
        int payDay
) {
    public static SalaryResponse from(Salary s) {
        int gross = s.gross();
        int net = (int) Math.round(gross * 0.91);   // 공제 약 9% 추정
        return new SalaryResponse(s.getBasePay(), s.getMealAllowance(), s.getTransportAllowance(),
                s.getPositionAllowance(), gross, net, s.getPayDay());
    }
}
