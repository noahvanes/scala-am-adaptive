(define (reverse lst)
  (define (go lst tail)
    (if (null? lst) tail
        (go (cdr lst) (cons (car lst) tail))))
  (go lst '()))

(define (comprimeer metingen)
  (define (hulp lst prev count)
    (cond ((null? lst) (list (list prev count)))
          ((= (car lst) prev)
           (hulp (cdr lst) prev (+ count 1)))
          (else (cons (list prev count) (hulp (cdr lst) (car lst) 1)))))
  (if (null? metingen)
      '()
      (hulp (cdr metingen) (car metingen) 1)))

(define (comprimeer-iter metingen)
  (define (hulp lst prev count res)
    (cond ((null? lst) (reverse (cons (list prev count) res)))
          ((= (car lst) prev)
           (hulp (cdr lst) prev (+ count 1) res))
          (else (hulp (cdr lst)
                      (car lst)
                      1
                      (cons (list prev count) res)))))
  (if (null? metingen)
      '()
      (hulp (cdr metingen) (car metingen) 1 '())))

(and (equal? (comprimeer '(37.5 37.5 37.2 38.0 38.0 38.0 38.3))
             '((37.5 2) (37.2 1) (38.0 3) (38.3 1)))
     (equal? (comprimeer-iter '(37.5 37.5 37.2 38.0 38.0 38.0 38.3))
             '((37.5 2) (37.2 1) (38.0 3) (38.3 1))))
