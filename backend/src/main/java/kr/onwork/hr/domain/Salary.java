package kr.onwork.hr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 직원 급여 정보(월). 마이페이지에서 본인만 조회. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "salaries")
public class Salary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 기본급(월, 원) */
    @Column(name = "base_pay", nullable = false)
    private int basePay;

    @Column(name = "meal_allowance", nullable = false)
    private int mealAllowance;

    @Column(name = "transport_allowance", nullable = false)
    private int transportAllowance;

    @Column(name = "position_allowance", nullable = false)
    private int positionAllowance;

    /** 급여 지급일(매월) */
    @Column(name = "pay_day", nullable = false)
    private int payDay;

    /** 세전 월 지급액 */
    public int gross() {
        return basePay + mealAllowance + transportAllowance + positionAllowance;
    }
}
