package kr.onwork.leave.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 휴가 종류 (leave_types): ANNUAL/COMP/HALF_AM/HALF_PM. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "leave_types")
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "days_unit", nullable = false)
    private BigDecimal daysUnit;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public boolean isHalfDay() {
        return "HALF_AM".equals(code) || "HALF_PM".equals(code);
    }

    public boolean isComp() {
        return "COMP".equals(code);
    }
}
